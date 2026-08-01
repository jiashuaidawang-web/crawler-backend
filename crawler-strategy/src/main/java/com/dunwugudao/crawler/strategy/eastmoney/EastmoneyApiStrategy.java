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
    private static final String PROXY_POOL_URL = "http://124.223.220.245:8088";
    private static final int PROXY_MAX_RETRIES = 8;

    private final AntiCrawlConfig antiCrawlConfig;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final EastmoneyClient client = new EastmoneyClient();
    private final ProxyClient proxyClient = new ProxyClient(PROXY_POOL_URL);
    private final RateLimiter rateLimiter;
    private final Random random = new Random();

    public EastmoneyApiStrategy(AntiCrawlConfig antiCrawlConfig) {
        this.antiCrawlConfig = antiCrawlConfig;
        this.rateLimiter = new RateLimiter(antiCrawlConfig.getRateLimitPerSec());
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

        switch (spec.getParserType()) {
            case CLIST: {
                String td = EastmoneyEndpoints.requireTradeDate(params);
                int page = 1;
                while (true) {
                    rateLimiter.acquire();
                    String url = spec.buildUrl(params, page);
                    String resp = fetchWithProxy(url, ua);
                    lastRaw = resp;
                    JsonNode root = readTree(resp);
                    JsonNode data = root.path("data");
                    allRows.addAll(parseClist(data, spec, td));
                    int totalPages = data.path("pages").asInt(1);
                    int curPage = data.path("page").asInt(page);
                    if (curPage >= totalPages) {
                        break;
                    }
                    page = curPage + 1;
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
                    String resp = fetchWithProxy(url, ua);
                    lastRaw = resp;
                    JsonNode root = readTree(resp);
                    List<Map<String, Object>> rows = (spec.getParserType() == EastmoneyEndpoints.ParserType.ZT_POOL)
                            ? parseZtPool(root.path("data"), spec, params)
                            : parseDatacenter(root.path("data"), spec, params);
                    if (rows.isEmpty()) {
                        break;
                    }
                    allRows.addAll(rows);
                    pageIdx++;
                }
                break;
            }
            case KLINE: {
                rateLimiter.acquire();
                String url = spec.buildUrl(params, 1);
                String resp = fetchWithProxy(url, ua);
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
        result.setRowCount(allRows.size());
        result.setHttpStatus(200);
        return result;
    }

    /**
     * 用代理池的 IP 发请求（每次换新 IP，失败重试）。
     * 成功 → report(true) → IP 回冷却池；失败 → report(false) → IP 丢弃 → 换新 IP 重试。
     */
    private String fetchWithProxy(String url, String ua) {
        for (int attempt = 0; attempt < PROXY_MAX_RETRIES; attempt++) {
            // 每次请求换新 IP
            ProxyClient.ProxyInfo proxyInfo = proxyClient.acquire();
            String proxy = proxyInfo != null ? proxyInfo.getProxy() : null;
            String supplier = proxyInfo != null ? proxyInfo.getSupplier() : "";

            try {
                long start = System.currentTimeMillis();
                String resp = client.get(url, ua, proxy);
                int latencyMs = (int) (System.currentTimeMillis() - start);
                // 成功 → report true → IP 回冷却池
                proxyClient.report(proxy != null ? proxy : "", true, latencyMs, supplier);
                return resp;
            } catch (Exception e) {
                // 失败 → report false → IP 丢弃 → 换新 IP 重试
                proxyClient.report(proxy != null ? proxy : "", false, 0, supplier);
            }
        }
        throw new RuntimeException("fetchWithProxy: all " + PROXY_MAX_RETRIES + " retries failed for " + url);
    }

    // ----------------------------------------------------------------------
    // 解析器
    // ----------------------------------------------------------------------

    private List<Map<String, Object>> parseClist(JsonNode data, EastmoneyEndpoints.EndpointSpec spec, String tradeDate) {
        List<Map<String, Object>> rows = new ArrayList<>();
        JsonNode diff = data.path("diff");
        if (!diff.isArray()) {
            return rows;
        }
        for (JsonNode n : diff) {
            Map<String, Object> row = new HashMap<>();
            switch (spec.getTaskType()) {
                case "BOARD_DAILY":
                    row.put("board_code", txt(n, "f12"));
                    row.put("board_name", txt(n, "f14"));
                    row.put("pct_chg", num(n, "f3"));
                    row.put("main_net", num(n, "f62"));
                    // TODO M6: board_daily.amount 东财 clist 无直接字段，暂置 NULL（需另取或下游聚合）
                    row.put("amount", null);
                    row.put("up_count", toInt(num(n, "f104")));
                    row.put("down_count", toInt(num(n, "f105")));
                    // TODO M6: limit_up_count 东财 clist 无直接字段，置 NULL
                    //          下游用 stock_board_rel × limit_pool 聚合计算
                    row.put("limit_up_count", null);
                    break;
                case "MAIN_FUND_STOCK":
                    row.put("obj_type", "stock");
                    row.put("ts_code", EastmoneyFieldMap.toTsCode(txt(n, "f12"), txt(n, "f13")));
                    row.put("main_net", num(n, "f62"));
                    row.put("super_big", num(n, "f66"));
                    row.put("big_net", num(n, "f72"));
                    row.put("mid_net", num(n, "f78"));
                    row.put("small_net", num(n, "f84"));
                    break;
                case "MAIN_FUND_BOARD":
                    row.put("obj_type", "board");
                    row.put("board_code", txt(n, "f12"));
                    row.put("main_net", num(n, "f62"));
                    row.put("super_big", num(n, "f66"));
                    row.put("big_net", num(n, "f72"));
                    row.put("mid_net", num(n, "f78"));
                    row.put("small_net", num(n, "f84"));
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
     */
    private List<Map<String, Object>> parseKline(JsonNode data, EastmoneyEndpoints.EndpointSpec spec, Map<String, Object> params) {
        List<Map<String, Object>> rows = new ArrayList<>();
        JsonNode klines = data.path("klines");
        if (!klines.isArray()) {
            return rows;
        }
        Double prevClose = null;
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
            if ("INDEX_DAILY".equals(spec.getTaskType())) {
                String idx = String.valueOf(params.get("indexCode"));
                row.put("index_code", idx);
                row.put("index_name", EastmoneyEndpoints.indexName(idx));
            } else {
                row.put("ts_code", String.valueOf(params.get("tsCode")));
                row.put("stock_name", txt(data, "name"));
            }
            prevClose = toDouble(f[2]);  // 记录当前 close 供下一行用
            row.put("trade_date", f[0]);
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
            // 价格
            row.put("pct_chg", num(n, "zdp"));
            row.put("close", num(n, "ztp"));             // 涨停价
            // 连板/封板
            Integer lbc = toInt(num(n, "lbc"));
            row.put("board_pos", lbc);                   // 连板数
            row.put("open_time", txt(n, "fbt"));         // 首次封板时间
            row.put("open_times", toInt(num(n, "zbc"))); // 开板次数
            row.put("last_time", txt(n, "lbt"));         // 最后封板时间（炸板）
            // 板块（实测无 hymc，板块名需下游另取）
            row.put("board_code", txt(n, "hybk"));
            row.put("board_name", null);                 // 实测响应不含 hymc
            // 资金/市值
            row.put("fund", num(n, "fund"));             // 封单资金（涨停池）
            row.put("amount", num(n, "amount"));         // 成交额
            row.put("ltsz", num(n, "ltsz"));             // 流通市值
            row.put("tshare", num(n, "tshare"));         // 总股本
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
            // 次新池特有
            row.put("ipod", txt(n, "ipod"));             // 上市日期
            row.put("o", num(n, "o"));                   // 开盘价
            row.put("od", toInt(num(n, "od")));          // 上市天数
            row.put("ods", toInt(num(n, "ods")));        // 上市天数
            // 限定
            row.put("type", lt);  // limit_up / limit_down / zhaban / strong / cixin
            // limit_style 近似：开板次数=0 且 09:30:00 封板 → 一字；否则 换手
            String fbt = txt(n, "fbt");
            Integer zbc = toInt(num(n, "zbc"));
            boolean isOneChar = (zbc != null && zbc == 0) && "09:30:00".equals(fbt);
            row.put("limit_style", isOneChar ? "一字" : "换手");
            row.put("is_first", (lbc != null && lbc == 1) ? 1 : 0);
            row.put("is_continuous", (lbc != null && lbc >= 2) ? 1 : 0);
            row.put("trade_date", tradeDate);
            rows.add(row);
        }
        return rows;
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
