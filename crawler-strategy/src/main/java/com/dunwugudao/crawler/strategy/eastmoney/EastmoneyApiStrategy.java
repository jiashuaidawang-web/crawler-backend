package com.dunwugudao.crawler.strategy.eastmoney;

import com.dunwugudao.crawler.core.config.AntiCrawlConfig;
import com.dunwugudao.crawler.core.model.CrawlContext;
import com.dunwugudao.crawler.core.model.CrawlResult;
import com.dunwugudao.crawler.core.model.CrawlTask;
import com.dunwugudao.crawler.core.model.SourceType;
import com.dunwugudao.crawler.core.strategy.SourceStrategy;
import com.dunwugudao.crawler.core.util.JsonCheckpoint;
import com.dunwugudao.crawler.core.util.RateLimiter;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.Proxy;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


/**
 * 东方财富 HTTP/JSON API 策略（M2 深化）。
 * <p>supports 返回 source == EASTMONEY。fetch 依据 {@code task.taskType} 从
 * {@link EastmoneyEndpoints} 取端点规格，构建 URL（注入 tradeDate/secid/分页），用
 * {@link EastmoneyClient} 执行；按 parserType 调用对应解析器，归一化为
 * {@code List<Map<String,Object>>}（key=schema 列名，必带 trade_date），设置 CrawlResult。</p>
 *
 * <p>能力：分页(clist 用 pages；池/Datacenter 用「返回空即停」)、字段映射对齐 PART A 原始表、
 * UA 随机 + 代理(perSource) + 令牌桶限速。失败抛 RuntimeException 交 worker RetryPolicy。</p>
 */
public class EastmoneyApiStrategy implements SourceStrategy {

    private static final int POOL_MAX_PAGES = 50; // 池/明细安全上限（配合「空即停」）

    private final AntiCrawlConfig antiCrawlConfig;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final EastmoneyClient client = new EastmoneyClient();
    /** Worker 级 IP 管理：1 worker = 1 IP，失败后才换新。替换原 ProxyClient 按请求取 IP。 */
    private WorkerProxyManager workerProxyManager;
    private final RateLimiter rateLimiter;
    private final Random random = new Random();

    public EastmoneyApiStrategy(AntiCrawlConfig antiCrawlConfig) {
        this.antiCrawlConfig = antiCrawlConfig;
        this.rateLimiter = new RateLimiter(antiCrawlConfig.getRateLimitPerSec());
    }

    /**
     * 注入 WorkerProxyManager（由 StrategyFactoryConfig 在装配时传入）。
     * 必须在 worker 启动前调用，否则 fetch 会因 manager 为 null 失败。
     */
    public void setWorkerProxyManager(WorkerProxyManager workerProxyManager) {
        this.workerProxyManager = workerProxyManager;
    }

    @Override
    public boolean supports(SourceType source) {
        return source == SourceType.EASTMONEY;
    }

    @Override
    public CrawlResult fetch(CrawlContext ctx) {
        CrawlTask task = ctx.getTask();
        Map<String, Object> params = JsonCheckpoint.deserialize(task.getParamsJson());
        String taskType = task.getTaskType();
        EastmoneyEndpoints.EndpointSpec spec = EastmoneyEndpoints.get(taskType);

        String ua = randomUa();

        List<Map<String, Object>> allRows = new ArrayList<>();
        String lastRaw = "";
        String lastUrl = "";

        switch (spec.getParserType()) {
            case CLIST: {
                String td = EastmoneyEndpoints.requireTradeDate(params);
                int page = 1;
                int totalPages = 1;
                while (true) {
                    rateLimiter.acquire();
                    String url = spec.buildUrl(params, page);
                    String resp = fetchWithWorkerProxy(url, ua);
                    lastRaw = resp;
                    JsonNode root = readTree(resp);
                    JsonNode data = root.path("data");
                    allRows.addAll(parseClist(data, spec, td, params));
                    // 翻页：优先用 pages；缺失则用 total+pz 算（push2 接口只有 total 无 pages）
                    int pagesFromField = data.path("pages").asInt(0);
                    if (pagesFromField > 0) {
                        totalPages = pagesFromField;
                    } else {
                        int total = data.path("total").asInt(0);
                        int pz = 200;
                        try {
                            pz = Integer.parseInt(String.valueOf(params.getOrDefault("pz", "200")));
                        } catch (NumberFormatException ignored) { }
                        totalPages = (total <= 0) ? 1 : ((total + pz - 1) / pz);
                    }
                    if (page >= totalPages) {
                        break;
                    }
                    page++;
                }
                break;
            }
            case ZT_POOL:
            case DATACENTER: {
                String td = EastmoneyEndpoints.requireTradeDate(params);
                int pageIdx = 0;
                while (pageIdx < POOL_MAX_PAGES) {
                    rateLimiter.acquire();
                    String url = spec.buildUrl(params, pageIdx);
                    String resp = fetchWithWorkerProxy(url, ua);
                    lastRaw = resp;
                    lastUrl = url;
                    String cleaned = cleanJsonp(resp);
                    JsonNode root = readTree(cleaned);
                    List<Map<String, Object>> rows;
                    if (spec.getParserType() == EastmoneyEndpoints.ParserType.ZT_POOL) {
                        rows = parseZtPool(root.path("data"), spec, params);
                        // 涨停/跌停/强势/次新：一次取全量，无需翻页
                        allRows.addAll(rows);
                        break;
                    } else {
                        rows = parseDatacenter(root.path("data"), spec, params);
                        if (rows.isEmpty()) {
                            break;
                        }
                        allRows.addAll(rows);
                        pageIdx++;
                    }
                }
                break;
            }
            case KLINE: {
                rateLimiter.acquire();
                String url = spec.buildUrl(params, 1);
                String resp = fetchWithWorkerProxy(url, ua);
                lastRaw = resp;
                JsonNode root = readTree(resp);
                allRows.addAll(parseKline(root.path("data"), spec, params));
                break;
            }
            default:
                throw new UnsupportedOperationException("parserType not handled: " + spec.getParserType());
        }

        CrawlResult result = new CrawlResult();
        result.setSuccess(true);
        result.setData(allRows);
        result.setRaw(lastRaw);
        result.setUrl(lastUrl);
        result.setRowCount(allRows.size());
        result.setHttpStatus(200);
        return result;
    }

    /**
     * 用 Worker 级代理发请求（极简方案：失败后才换新 IP）。
     * <p>与原 {@code fetchWithProxy} 的关键区别：</p>
     * <ul>
     *   <li>不再每次换新 IP——用 worker 当前绑定的同一个 IP。</li>
     *   <li>代理级错误（连接超时/重置/SSL/407）→ 标记失效 → 抛异常 → ClaimLoop 重试时自动换新 IP。</li>
     *   <li>业务错误（HTTP 200 但 rc:102）→ 不标记失效 → 正常返回空数据。</li>
     * </ul>
     */
    private String fetchWithWorkerProxy(String url, String ua) {
        if (workerProxyManager == null) {
            throw new IllegalStateException("WorkerProxyManager 未注入，请在装配时调用 setWorkerProxyManager()");
        }
        String proxy = workerProxyManager.getProxy();
        try {
            return client.get(url, ua, proxy);
        } catch (Exception e) {
            // 判断是否为代理级错误（需要换 IP）vs 业务错误（IP 没问题）
            if (isProxyFailure(e)) {
                workerProxyManager.invalidate();
            }
            throw e;
        }
    }

    /**
     * 判断异常是否由代理/IP 问题引起（需要换新 IP），而非目标站点业务错误。
     * 代理级错误特征：连接超时、连接重置、SSL 握手失败、407 认证失败、无法建立隧道。
     */
    private static boolean isProxyFailure(Exception e) {
        String msg = e.getMessage() == null ? "" : e.getMessage().toLowerCase();
        String cls = e.getClass().getSimpleName().toLowerCase();
        // 连接层面错误
        if (cls.contains("sockettimeout") && msg.contains("connect")) return true;
        if (cls.contains("connectexception") && msg.contains("refused")) return true;
        if (cls.contains("socketexception") && msg.contains("reset")) return true;
        if (cls.contains("ssl") || cls.contains("handshake")) return true;
        // HTTP 407 代理认证失败
        if (msg.contains("407") || msg.contains("proxy authentication")) return true;
        // 无法建立隧道（HTTPS through proxy）
        if (msg.contains("unable to tunnel") || msg.contains("tunnel")) return true;
        // 连接超时（读）通常是代理慢/挂了
        if (cls.contains("sockettimeout") && msg.contains("read")) return true;
        // 业务错误（东财返回 rc:102 但 HTTP 200）不在此列——那些是正常响应，不抛异常
        return false;
    }

    // ----------------------------------------------------------------------
    // 解析器
    // ----------------------------------------------------------------------

    private List<Map<String, Object>> parseClist(JsonNode data, EastmoneyEndpoints.EndpointSpec spec, String tradeDate, Map<String, Object> params) {
        List<Map<String, Object>> rows = new ArrayList<>();
        JsonNode diff = data.path("diff");
        if (!diff.isArray()) {
            return rows;
        }
        for (JsonNode n : diff) {
            Map<String, Object> row = new HashMap<>();
            switch (spec.getTaskType()) {
                case "BOARD_DAILY":
                case "REGION_DAILY":
                case "INDUSTRY_DAILY":
                case "CONCEPT_DAILY":
                    row.put("board_code", txt(n, "f12"));
                    row.put("board_name", txt(n, "f14"));
                    row.put("board_type", parseInt(params.get("boardType")));
                    row.put("pct_chg", num(n, "f3"));
                    row.put("main_net", num(n, "f62"));
                    // 行情明细（基于实测 f 码映射）
                    row.put("price", num(n, "f2"));                   // 价格（收盘价）
                    row.put("rise_fall", num(n, "f4"));               // 涨跌额
                    row.put("volume", num(n, "f5"));                  // 成交量（手）
                    row.put("amplitude", num(n, "f7"));               // 振幅%
                    row.put("high_price", num(n, "f15"));             // 最高价格
                    row.put("low_price", num(n, "f16"));              // 最低价格
                    row.put("today_open_price", num(n, "f17"));       // 今开
                    row.put("yesterday_received_price", num(n, "f18")); // 昨收
                    row.put("volume_ratio", num(n, "f10"));           // 量比
                    row.put("turnover_ratio", num(n, "f8"));          // 换手率%
                    row.put("total_market_value", num(n, "f20"));     // 总市值
                    row.put("circulation_market_value", num(n, "f21")); // 流通市值
                    row.put("amount", num(n, "f6"));                  // 成交额(元)
                    row.put("up_count", toInt(num(n, "f104")));
                    row.put("down_count", toInt(num(n, "f105")));
                    row.put("leading_code", txt(n, "f166"));        // 领涨股代码
                    row.put("leading_name", txt(n, "f167"));        // 领涨股名称
                    // TODO M6: limit_up_count 东财 clist 无直接字段，置 NULL
                    //          下游用 stock_board_rel × limit_pool 聚合计算
                    row.put("limit_up_count", null);
                    break;
                case "MAIN_FUND_STOCK":
                    row.put("obj_type", "stock");
                    row.put("ts_code", EastmoneyFieldMap.toTsCode(txt(n, "f12"), txt(n, "f13")));
                    // 主键五列 NOT NULL：个股级只对应 ts_code，其余代码列填占位 "0"
                    row.put("board_code", "0");
                    row.put("index_code", "0");
                    row.put("main_net", num(n, "f62"));
                    row.put("super_big", num(n, "f66"));
                    row.put("big_net", num(n, "f72"));
                    row.put("mid_net", num(n, "f78"));
                    row.put("small_net", num(n, "f84"));
                    break;
                case "MAIN_FUND_BOARD":
                    row.put("obj_type", "board");
                    row.put("board_code", txt(n, "f12"));
                    // 主键五列 NOT NULL：板块级只对应 board_code，其余代码列填占位 "0"
                    row.put("ts_code", "0");
                    row.put("index_code", "0");
                    row.put("main_net", num(n, "f62"));
                    row.put("super_big", num(n, "f66"));
                    row.put("big_net", num(n, "f72"));
                    row.put("mid_net", num(n, "f78"));
                    row.put("small_net", num(n, "f84"));
                    break;
                case "STOCK_BY_BOARD":
                    // 板块-个股关联：board 信息从 params 传入（seed 时从 board_basic 表补），股票信息从 diff 取
                    // is_leader/is_midarm/weight 东财接口不返回，暂置 null（TODO M6 下游算）
                    row.put("board_code", String.valueOf(params.getOrDefault("boardCode", "")));
                    row.put("board_name", String.valueOf(params.getOrDefault("boardName", "")));
                    row.put("board_type", parseInt(params.get("boardType")));
                    row.put("ts_code", EastmoneyFieldMap.toTsCode(txt(n, "f12"), txt(n, "f13")));
                    row.put("is_leader", null);
                    row.put("is_midarm", null);
                    row.put("weight", null);
                    break;
                default:
                    // 通用兜底：用 EastmoneyFieldMap 投影
                    for (var it = n.fields(); it.hasNext(); ) {
                        var e = it.next();
                        String col = EastmoneyFieldMap.schemaCol(e.getKey());
                        if (col != null) {
                            row.put(col, numOrText(e.getValue()));
                        }
                    }
                    break;
            }
            row.put("trade_date", tradeDate);
            rows.add(row);
        }
        return rows;
    }

    /**
     * 解析 kline（日/周线）。
     * <p>fields2 固定顺序：日期,开盘,收盘,最高,最低,成交量,成交额,振幅,涨跌幅,涨跌额,换手率。
     * pre_close 用上一行 close 推算（首行用当天 close 兜底）。</p>
     * <p>列裁剪：INDEX_DAILY 只写指数表有的列（open/high/low/close/pre_close/pct_chg/vol/amount/turnover），
     * 不携带个股专属列（total_mv/circ_mv/pe/is_limit_up 等）。STOCK_DAILY / STOCK_WEEKLY 各自按对应表字段写。</p>
     */
    private List<Map<String, Object>> parseKline(JsonNode data, EastmoneyEndpoints.EndpointSpec spec, Map<String, Object> params) {
        List<Map<String, Object>> rows = new ArrayList<>();
        JsonNode klines = data.path("klines");
        if (!klines.isArray()) {
            return rows;
        }
        Double prevClose = null;
        String taskType = spec.getTaskType();
        boolean isIndex = "INDEX_DAILY".equals(taskType);
        boolean isWeekly = "STOCK_WEEKLY".equals(taskType);
        for (JsonNode line : klines) {
            String[] f = line.asText().split(",");
            if (f.length < 11) {
                continue;
            }
            Map<String, Object> row = new HashMap<>();
            row.put("trade_date", f[0]);                       // f51
            row.put("open", toDouble(f[1]));                   // f52
            row.put("close", toDouble(f[2]));                  // f53
            row.put("high", toDouble(f[3]));                   // f54
            row.put("low", toDouble(f[4]));                    // f55
            row.put("vol", toDouble(f[5]));                    // f56 成交量(手)
            row.put("amount", toDouble(f[6]));                 // f57 成交额(元)
            row.put("amplitude", toDouble(f[7]));              // f58 振幅%
            row.put("pct_chg", toDouble(f[8]));                // f59 涨跌幅%
            row.put("chg_amount", toDouble(f[9]));             // f60 涨跌额
            row.put("turnover", toDouble(f[10]));              // f61 换手率%
            // pre_close：首行用当天 close 兜底，后续用上一行 close
            row.put("pre_close", prevClose != null ? prevClose : toDouble(f[2]));

            if (isIndex) {
                // 指数日线：仅指数表列，不携带个股专属字段
                String idx = String.valueOf(params.get("indexCode"));
                row.put("index_code", idx);
                row.put("index_name", EastmoneyEndpoints.indexName(idx));
            } else if (isWeekly) {
                // 个股周线：周线表列（无 total_mv/circ_mv/pe/is_limit_up 等日线专属）
                row.put("ts_code", String.valueOf(params.get("tsCode")));
                row.put("stock_name", txt(data, "name"));
            } else {
                // 个股日线：写真实 kline 列；其余 stock_daily 列（total_mv/circ_mv/pe/is_limit_up 等）置 null
                row.put("ts_code", String.valueOf(params.get("tsCode")));
                row.put("stock_name", txt(data, "name"));
                // TODO M6: total_mv/circ_mv/pe/is_limit_up/leader_code/industry_code 等需其它接口补全
                row.put("total_mv", null);
                row.put("circ_mv", null);
                row.put("pe", null);
                row.put("is_limit_up", null);
                row.put("is_limit_down", null);
                row.put("volume_ratio", null);
                row.put("avg_price", null);
                row.put("main_net", null);
                row.put("pe_static", null);
                row.put("leader_code", null);
                row.put("industry_code", null);
                row.put("concept_code", null);
                row.put("market_code", null);
            }

            prevClose = toDouble(f[2]);  // 记录当前 close 供下一行用
            rows.add(row);
        }
        return rows;
    }

    /**
     * 解析 push2ex 涨跌停/炸板/强势/次新池。
     * <p>基于实测字段（2026-08-01）：amount,c,fbt,fund,hs,hybk,lbc,lbt,ltsz,m,n,p,tshare,zbc,zdp,zttj.ct,zttj.days。
     * 注意：实测响应中 <b>不含 hymc（板块名称）</b>，板块名需下游另取。</p>
     */
    private List<Map<String, Object>> parseZtPool(JsonNode data, EastmoneyEndpoints.EndpointSpec spec, Map<String, Object> params) {
        List<Map<String, Object>> rows = new ArrayList<>();
        JsonNode pool = data.path("pool");
        if (!pool.isArray()) {
            return rows;
        }
        String tradeDate = EastmoneyEndpoints.requireTradeDate(params);
        String lt = String.valueOf(params.getOrDefault("limitType", spec.getPoolDefaultLimitType()));
        for (JsonNode n : pool) {
            Map<String, Object> row = new HashMap<>();
            // 代码/名称
            row.put("ts_code", EastmoneyFieldMap.toTsCode(txt(n, "c"), txt(n, "m")));
            row.put("stock_name", txt(n, "n"));
            // 最新价（接口 p 单位是分，转元 ÷100）
            Double p = num(n, "p");
            row.put("latest_price", p != null ? p / 100.0 : null);
            // 涨跌幅（接口 zdp 已经是百分比数值）
            row.put("pct_chg", num(n, "zdp"));
            // 涨停价（接口 ztp 单位是分，转元 ÷100）
            Double ztpRaw = num(n, "ztp");
            row.put("ztp", ztpRaw != null ? ztpRaw / 100.0 : null);
            // 连板/封板
            Integer lbc = toInt(num(n, "lbc"));
            row.put("board_pos", lbc);                   // 连板数
            // 首次/最后封板时间（接口 fbt/lbt 是 HHMMSS 格式 92500 = 09:25:00）
            row.put("open_time", hhmmssToTime(txt(n, "fbt")));
            row.put("last_time", hhmmssToTime(txt(n, "lbt")));
            row.put("open_times", toInt(num(n, "zbc"))); // 开板次数 = 炸板次数
            // 板块（实测无 hymc，hybk 是行业代码不是名称）
            row.put("board_code", txt(n, "hybk"));
            row.put("board_name", null);                 // 实测响应不含 hymc
            // 资金/市值（接口单位是元，表存亿元 ÷1e8）
            row.put("fund", num(n, "fund"));             // 封单资金(元) → 下游转亿
            row.put("amount", num(n, "amount"));         // 成交额(元)
            row.put("ltsz", num(n, "ltsz"));             // 流通市值(元)
            row.put("tshare", num(n, "tshare"));         // 总市值(元)
            row.put("hs", num(n, "hs"));                 // 换手率（接口是百分比数值）
            // 连板统计（嵌套对象 zttj.ct / zttj.days）
            row.put("zttj_ct", toInt(num(n, "zttj.ct")));
            row.put("zttj_days", toInt(num(n, "zttj.days")));
            // 炸板池特有
            row.put("zf", num(n, "zf"));                 // 涨幅%
            row.put("zs", num(n, "zs"));                 // 振幅%
            // 强势池特有
            row.put("lb", toInt(num(n, "lb")));          // 连板数
            row.put("nh", toInt(num(n, "nh")));          // N日新高
            row.put("ztf", txt(n, "ztf"));               // 涨停封单描述
            // 次新池特有（按东财字段表）
            // ztp：涨停价（分），1000000000=无涨停限制 → 存 NULL
            Double ztpRawVal = num(n, "ztp");
            if (ztpRawVal != null && ztpRawVal >= 1000000000.0) {
                row.put("ztp", null);
            } else {
                row.put("ztp", ztpRawVal != null ? ztpRawVal / 100.0 : null);
            }
            row.put("ipod", txt(n, "ipod"));             // 上市日期（原始格式 YYYYMMDD）
            row.put("o", toInt(num(n, "o")));            // 是否新高标识（1=新高，0=非新高）
            row.put("od", toInt(num(n, "od")));          // 开板日期（原始格式 YYYYMMDD）
            row.put("ods", toInt(num(n, "ods")));        // 开板几日
            // 限定
            row.put("type", lt);  // limit_up / limit_down / zhaban / strong / cixin
            // limit_style 近似：开板次数=0 且 09:30:00 封板 → 一字；否则 换手
            String fbt = txt(n, "fbt");
            Integer zbc = toInt(num(n, "zbc"));
            boolean isOneChar = (zbc != null && zbc == 0) && "92500".equals(fbt);
            row.put("limit_style", isOneChar ? "一字" : "换手");
            row.put("is_first", (lbc != null && lbc == 1) ? 1 : 0);
            row.put("is_continuous", (lbc != null && lbc >= 2) ? 1 : 0);
            row.put("trade_date", tradeDate);
            rows.add(row);
        }
        return rows;
    }

    /** 秒级时间戳（如 92500 = 09:25:00）转 HH:mm:ss。 */
    /**
     * 东财 fbt/lbt 格式转换：接口返回 HHMMSS（如 92500 = 092500 = 09:25:00）。
     * <p>注意：不是秒数！是 6 位数字 hhmmss。</p>
     */
    private static String hhmmssToTime(String raw) {
        if (raw == null || raw.isEmpty()) return null;
        // 右补 0 到 6 位（如 92500 → 092500）
        String s = String.format("%06d", Integer.parseInt(raw));
        if (s.length() != 6) return null;
        return s.substring(0, 2) + ":" + s.substring(2, 4) + ":" + s.substring(4, 6);
    }

    /**
     * 解析 datacenter 龙虎榜（v1 端点）。
     * <p>基于实测字段（2026-08-01，30字段）：SECUCODE(带后缀), SECURITY_CODE(无后缀),
     * SECURITY_NAME_ABBR, EXPLAIN, BILLBOARD_BUY_AMT, BILLBOARD_SELL_AMT, BILLBOARD_NET_AMT,
     * MARKET, CLOSE_PRICE, CHANGE_RATE, TURNOVERRATE, FREE_MARKET_CAP, BUY_SEAT, SELL_SEAT 等。</p>
     * <p>实测发现：<b>SECUCODE 已带市场后缀</b>（如 000009.SZ），无需启发式补后缀；
     * <b>MARKET 是显式字段</b>（如 SZ/BJ）。</p>
     */
    private List<Map<String, Object>> parseDatacenter(JsonNode data, EastmoneyEndpoints.EndpointSpec spec, Map<String, Object> params) {
        List<Map<String, Object>> rows = new ArrayList<>();
        if (!data.isArray()) {
            return rows;
        }
        String tradeDate = EastmoneyEndpoints.requireTradeDate(params);
        if ("DRAGON_TIGER".equals(spec.getTaskType())) {
            for (JsonNode n : data) {
                Map<String, Object> row = new HashMap<>();
                // 代码：优先用 SECUCODE（已带后缀），否则用 SECURITY_CODE
                String secucode = txt(n, "SECUCODE");
                row.put("ts_code", (secucode != null && !secucode.isEmpty()) ? secucode : tsCodeFromRaw(txt(n, "SECURITY_CODE")));
                row.put("stock_name", txt(n, "SECURITY_NAME_ABBR"));
                row.put("reason", txt(n, "EXPLAIN"));
                row.put("explanation", txt(n, "EXPLANATION"));
                // 买卖金额
                row.put("total_buy", num(n, "BILLBOARD_BUY_AMT"));
                row.put("total_sell", num(n, "BILLBOARD_SELL_AMT"));
                row.put("net_buy", num(n, "BILLBOARD_NET_AMT"));
                row.put("billboard_deal_amt", num(n, "BILLBOARD_DEAL_AMT"));
                // 市场/价格
                row.put("market", txt(n, "MARKET"));         // 显式市场字段（SZ/BJ/SH）
                row.put("close_price", num(n, "CLOSE_PRICE"));
                row.put("change_rate", num(n, "CHANGE_RATE"));
                row.put("turnoverrate", num(n, "TURNOVERRATE"));
                row.put("free_market_cap", num(n, "FREE_MARKET_CAP"));
                // 席位
                row.put("buy_seat", toInt(num(n, "BUY_SEAT")));
                row.put("sell_seat", toInt(num(n, "SELL_SEAT")));
                row.put("buy_seat_new", toInt(num(n, "BUY_SEAT_NEW")));
                row.put("sell_seat_new", toInt(num(n, "SELL_SEAT_NEW")));
                row.put("buy_ratio", num(n, "BUY_RATIO"));
                row.put("sell_ratio", num(n, "SELL_RATIO"));
                // 其他
                row.put("accum_amount", num(n, "ACCUM_AMOUNT"));
                row.put("deal_amount_ratio", num(n, "DEAL_AMOUNT_RATIO"));
                row.put("deal_net_ratio", num(n, "DEAL_NET_RATIO"));
                row.put("change_type", txt(n, "CHANGE_TYPE"));
                row.put("security_inner_code", txt(n, "SECURITY_INNER_CODE"));
                row.put("security_type_code", txt(n, "SECURITY_TYPE_CODE"));
                row.put("trade_id", txt(n, "TRADE_ID"));
                row.put("trade_market", txt(n, "TRADE_MARKET"));
                row.put("trade_market_code", txt(n, "TRADE_MARKET_CODE"));
                row.put("trade_date", tradeDate);
                rows.add(row);
            }
        } else { // DRAGON_TIGER_DETAIL（报表名待确认，当前为占位）
            String tsCode = tsCodeFromRaw(String.valueOf(params.get("code")));
            for (JsonNode n : data) {
                Map<String, Object> row = new HashMap<>();
                row.put("ts_code", tsCode);
                row.put("seat_name", txt(n, "SEAT_NAME"));
                String seatType = txt(n, "SEAT_TYPE");
                row.put("seat_type", seatType);
                row.put("buy", num(n, "BUY"));
                row.put("sell", num(n, "SELL"));
                row.put("is_institution", (seatType != null && seatType.contains("机构")) ? 1 : 0);
                // TODO M6: is_famous 需维护知名游资名单，当前置 0
                row.put("is_famous", 0);
                row.put("trade_date", tradeDate);
                rows.add(row);
            }
        }
        return rows;
    }

    // ----------------------------------------------------------------------
    // 工具
    // ----------------------------------------------------------------------

    private JsonNode readTree(String resp) {
        try {
            return objectMapper.readTree(resp);
        } catch (Exception e) {
            throw new RuntimeException("Eastmoney JSON parse failed: " + e.getMessage(), e);
        }
    }

    /**
     * 清洗 JSONP 包裹：东财 push2ex / datacenter 返回 {@code callback({...});}，
     * 需剥掉前缀（第一个 '(' 之前）与后缀（最后一个 ');'）才是合法 JSON。
     * <p>非 JSONP（纯 JSON / 空串）原样返回。</p>
     */
    private String cleanJsonp(String raw) {
        if (raw == null) {
            return null;
        }
        String s = raw.trim();
        int lparen = s.indexOf('(');
        int rparen = s.lastIndexOf(')');
        if (lparen > 0 && rparen > lparen && s.endsWith(");")) {
            return s.substring(lparen + 1, rparen);
        }
        return s;
    }

    private String randomUa() {
        List<String> pool = antiCrawlConfig.getUaPool();
        if (pool == null || pool.isEmpty()) {
            return "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Safari/537.36";
        }
        return pool.get(random.nextInt(pool.size()));
    }

    private static String txt(JsonNode n, String field) {
        JsonNode v = n.get(field);
        if (v == null || v.isNull()) {
            return null;
        }
        String s = v.asText();
        return s.isEmpty() ? null : s;
    }

    private static Double num(JsonNode n, String field) {
        JsonNode v = n.get(field);
        if (v == null || v.isNull()) {
            return null;
        }
        String s = v.asText();
        if (s == null || s.isEmpty() || "-".equals(s) || "--".equals(s)) {
            return null;
        }
        try {
            return Double.parseDouble(s);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** 通用兜底：数字转 Double，否则保留文本。 */
    private static Object numOrText(JsonNode v) {
        if (v == null || v.isNull()) {
            return null;
        }
        String s = v.asText();
        if (s == null || s.isEmpty() || "-".equals(s)) {
            return null;
        }
        try {
            return Double.parseDouble(s);
        } catch (NumberFormatException e) {
            return s;
        }
    }

    private static Integer toInt(Double d) {
        return d == null ? null : d.intValue();
    }

    /** Object（params 传入）→ Integer，null/空串兜底为 null。 */
    private static Integer parseInt(Object o) {
        if (o == null) {
            return null;
        }
        if (o instanceof Number n) {
            return n.intValue();
        }
        String s = String.valueOf(o).trim();
        if (s.isEmpty() || "-".equals(s)) {
            return null;
        }
        try {
            return Integer.parseInt(s);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static Double toDouble(String s) {
        if (s == null || s.isEmpty() || "-".equals(s)) {
            return null;
        }
        try {
            return Double.parseDouble(s);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** datacenter 缺少显式市场字段时的代码前缀启发式（TODO M6 核对真实字段）。 */
    private static String tsCodeFromRaw(String code) {
        if (code == null) {
            return null;
        }
        code = code.trim();
        if (code.startsWith("6")) {
            return code + ".SH";
        }
        if (code.startsWith("0") || code.startsWith("3")) {
            return code + ".SZ";
        }
        if (code.startsWith("8") || code.startsWith("4")) {
            return code + ".BJ";
        }
        return code; // 未知，保留原样
    }
}
