package com.dunwugudao.crawler.strategy.eastmoney;

import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.util.HashMap;
import java.util.Map;
import java.util.Random;

/**
 * 按 taskType 路由的东财端点表（M2 深化）。
 * <p>每个 {@link EndpointSpec} 描述：baseUrl、是否需要 secid(kline 类)、字段投影、解析器类型、
 * 以及构建 URL 的方法。未实现的 taskType 在 {@link #get(String)} 抛
 * {@link UnsupportedOperationException} 并留 TODO。</p>
 *
 * <p>覆盖：LIMIT_POOL / STOCK_DAILY / STOCK_WEEKLY / INDEX_DAILY / REGION_DAILY /
 * INDUSTRY_DAILY / CONCEPT_DAILY / MAIN_FUND_STOCK / MAIN_FUND_BOARD / DRAGON_TIGER / DRAGON_TIGER_DETAIL。</p>
 */
public final class EastmoneyEndpoints {

    public enum ParserType {
        CLIST,      // push2 clist（板块/资金流分页）
        KLINE,      // push2his kline（个股/指数日周线）
        ZT_POOL,    // push2ex 涨停/跌停/炸板池
        DATACENTER, // datacenter-web 龙虎榜
        KAMT        // push2 kamt 北向资金实时端点（纯 JSON，非 JSONP）
    }

    /** 端点规格（数据持有 + URL 构建）。 */
    @lombok.Data
    @lombok.AllArgsConstructor
    public static class EndpointSpec {
        String taskType;
        String baseUrl;
        ParserType parserType;
        String fs;        // CLIST 的 fs 过滤串（板块/资金流不同）
        String dcType;    // DATACENTER 的 type 参数
        String poolDefaultLimitType; // ZT_POOL 默认 limit_type 列值（可被 params.limitType 覆盖）

        /** 构建请求 URL（page 对 CLIST/池有意义，DATACENTER 忽略）。 */
    public String buildUrl(Map<String, Object> params, int page) {
            switch (parserType) {
                case ZT_POOL: {
                    // 涨停/跌停/强势/次新池：一个任务由 worker 翻页抓全量。
                    // Pageindex 用 buildUrl 的 page 参数（循环下标），不要读种子里写死的 Pageindex，
                    // 否则 worker 翻页无效，只会反复打第 0 页。
                    // 排序：涨停/炸板 fbt asc；跌停用 fund asc；强势/次新用 zdp desc（用户实测 2026-08-02/03）
                    String d = tradeDate(params).replace("-", "");
                    long ts = generateTs();
                    int pageindex = Math.max(page, 0);
                    int pagesize = parseInt(params.getOrDefault("pagesize", 100), 100);
                    // limitType 兼容两套命名：种子传 taskType 大写（LIMIT_DOWN/STRONG_POOL），spec 默认小写（down/strong）
                    // 统一转小写后用 contains 匹配，兼容 limit_down 与 down、strong_pool 与 strong 等
                    String lt = String.valueOf(params.getOrDefault("limitType", this.getPoolDefaultLimitType())).toLowerCase();
                    String path;
                    if (lt.contains("down")) {
                        path = "getTopicDTPool";
                    } else if (lt.contains("zhaban")) {
                        path = "getTopicZBPool";
                    } else if (lt.contains("strong")) {
                        path = "getTopicQSPool";
                    } else if (lt.contains("cixin")) {
                        path = "getTopicCXPooll";
                    } else {
                        path = "getTopicZTPool"; // 涨停 / 默认
                    }
                    String sort;
                    if (lt.contains("down")) {
                        sort = "fund%3Aasc";
                    } else if (lt.contains("strong") || lt.contains("cixin")) {
                        sort = "zdp%3Adesc";
                    } else {
                        sort = "fbt%3Aasc";
                    }
                    // 协议用 HTTP（与 stockDaily 一致）：HTTPS 经代理时 CONNECT 隧道阶段
                    // Proxy-Authorization 加不上，会触发 407 认证失败（10 个 IP 全失败）。
                    // 改 HTTP 后无 CONNECT 隧道，应用拦截器的 Proxy-Authorization 头生效。
                    return "http://push2ex.eastmoney.com/" + path
                            + "?cb=" + generateCb() + "&ut=7eea3edcaed734bea9cbfc24409ed989&dpt=wz.ztzt"
                            + "&date=" + d + "&Pageindex=" + pageindex + "&pagesize=" + pagesize
                            + "&sort=" + sort + "&_=" + ts;
                }
                case KLINE: {
                    String secid = secidFor(taskType, params);
                    // klt：101=日线, 102=周线, 1=分钟线
                    String klt;
                    if ("STOCK_WEEKLY".equals(taskType)) {
                        klt = "102";
                    } else if ("STOCK_KLINE_MINUTE".equals(taskType)) {
                        klt = "1";
                    } else {
                        klt = "101"; // STOCK_DAILY / STOCK_DAILY_HISTORY / INDEX_DAILY
                    }
                    // 严格按 market 项目 URL 格式：ut + cb + _ + lmt=50000 + end=20500101（固定值，不从 params 读）
                    String cb = "jQuery" + System.currentTimeMillis() + "_" + new Random().nextLong();
                    long ts = System.currentTimeMillis();
                    return baseUrl
                            + "?cb=" + cb
                            + "&secid=" + secid
                            + "&ut=fa5fd1943c7b386f172d6893dbfba10b"
                            + "&fields1=f1%2Cf2%2Cf3%2Cf4%2Cf5%2Cf6"
                            + "&fields2=f51%2Cf52%2Cf53%2Cf54%2Cf55%2Cf56%2Cf57%2Cf58%2Cf59%2Cf60%2Cf61"
                            + "&klt=" + klt
                            + "&fqt=0"
                            + "&end=20500101"
                            + "&lmt=50000"
                            + "&_=" + ts;
                }
                case CLIST: {
                    String fsVal;
                    // STOCK_BY_BOARD 的 fs 需要动态拼：b:bk{boardCode}+f:!50
                    if ("STOCK_BY_BOARD".equals(taskType)) {
                        String bc = String.valueOf(params.getOrDefault("boardCode", ""));
                        String bk = bc.toLowerCase(); // board_basic 存 BK0450，接口要小写 bk0450
                        fsVal = "b:" + bk + "+f:!50";
                    } else {
                        fsVal = fs != null ? fs : String.valueOf(params.getOrDefault("fs", ""));
                    }
                    String fields = String.valueOf(params.getOrDefault("fields", defaultFields(taskType)));
                    // 页码：STOCK_DAILY 由种子注入 pn（从 1 开始），其它 CLIST 沿用 page 参数
                    int pn;
                    if ("STOCK_DAILY".equals(taskType)) {
                        pn = parseInt(params.getOrDefault("pn", page), page);
                    } else {
                        pn = page;
                    }
                    // 完整 push2 模板(用户实测 2026-08-04)：域名/协议/参数一个不少,避免被识别为爬虫
                    long ts = generateTs();
                    String cb = generateCb();
                    // 支持自定义 pz(默认 100),用于全量拉取板块列表
                    int pz = parseInt(params.getOrDefault("pz", 100), 100);
                    return "http://83.push2.eastmoney.com/api/qt/clist/get"
                            + "?cb=" + cb
                            + "&pn=" + pn + "&pz=" + pz + "&po=1&np=1"
                            + "&ut=bd1d9ddb04089700cf9c27f6f7426281"
                            + "&fltt=2&invt=2"
                            + "&fid=f3"
                            + "&fs=" + fsVal
                            + "&fields=" + fields
                            + "&_=" + ts;
                }
                case DATACENTER: {
                    String td = tradeDate(params);
                    // 东财 datacenter 接口已于近期改造：旧版 /api/data/get?type= 强制要求返回字段参数
                    // 且参数格式不兼容（固定返回 9501 "返回字段参数不能为空"）。统一切到新版
                    // /api/data/v1/get + reportName + columns（字段名参考 akshare stock_lhb_detail_em）。
                    // 日期统一转 yyyy-MM-dd（新版 filter 用带横线格式）。
                    String td8 = td == null ? "" : td.replace("-", "");
                    String tdDash = td8.length() == 8
                            ? td8.substring(0, 4) + "-" + td8.substring(4, 6) + "-" + td8.substring(6, 8)
                            : td;
                    try {
                        if ("DRAGON_TIGER".equals(taskType)) {
                            String columns = "SECURITY_CODE,SECUCODE,SECURITY_NAME_ABBR,TRADE_DATE,EXPLAIN,"
                                    + "CLOSE_PRICE,CHANGE_RATE,BILLBOARD_NET_AMT,BILLBOARD_BUY_AMT,BILLBOARD_SELL_AMT,"
                                    + "BILLBOARD_DEAL_AMT,ACCUM_AMOUNT,DEAL_NET_RATIO,DEAL_AMOUNT_RATIO,TURNOVERRATE,"
                                    + "FREE_MARKET_CAP,EXPLANATION,MARKET,SECURITY_TYPE_CODE,SECURITY_INNER_CODE,"
                                    + "TRADE_ID,TRADE_MARKET,TRADE_MARKET_CODE,BUY_SEAT,SELL_SEAT,BUY_SEAT_NEW,"
                                    + "SELL_SEAT_NEW,BUY_RATIO,SELL_RATIO,CHANGE_TYPE,"
                                    + "NET_BS_AMT,SUM_BUY_AMT,SUM_SELL_AMT,"
                                    + "D1_CLOSE_ADJCHRATE,D2_CLOSE_ADJCHRATE,D5_CLOSE_ADJCHRATE,"
                                    + "D10_CLOSE_ADJCHRATE,D20_CLOSE_ADJCHRATE,D30_CLOSE_ADJCHRATE";
                            String filter = "(TRADE_DATE<='" + tdDash + "')(TRADE_DATE>='" + tdDash + "')";
                            return "https://datacenter-web.eastmoney.com/api/data/v1/get"
                                    + "?reportName=RPT_DAILYBILLBOARD_DETAILSNEW"
                                    + "&columns=" + URLEncoder.encode(columns, "UTF-8")
                                    + "&sortColumns=SECURITY_CODE,TRADE_DATE&sortTypes=1,-1"
                                    + "&pageSize=5000&pageNumber=1"
                                    + "&source=WEB&client=WEB"
                                    + "&filter=" + URLEncoder.encode(filter, "UTF-8");
                        } else { // DRAGON_TIGER_DETAIL —— 龙虎榜席位明细（RPT_BILLBOARD_SEAT）
                            // 按主表 TRADE_ID 关联（RPT_BILLBOARD_SEAT 不支持 SECURITY_CODE 过滤）
                            String tradeId = String.valueOf(params.get("tradeId"));
                            String filter = "(TRADE_ID=" + tradeId + ")";
                            return "https://datacenter-web.eastmoney.com/api/data/v1/get"
                                    + "?reportName=RPT_BILLBOARD_SEAT"
                                    + "&columns=" + URLEncoder.encode(
                                            "SECUCODE,SECURITY_CODE,SECURITY_NAME_ABBR,TRADE_ID,TRADE_DATE,"
                                            + "CHANGE_TYPE,EXPLANATION,START_DATE,END_DATE,STATISTICS_DAYS,"
                                            + "ACCUM_VOLUME,ACCUM_AMOUNT,CHANGE_RATE,TURNOVERRATE_RATIO,TRADE_DIRECTION,"
                                            + "RANK,OPERATEDEPT_CODE,OPERATEDEPT_NAME,OPERATEDEPT_TYPE,"
                                            + "BUY_AMT,BUY_RATIO,SELL_AMT,SELL_RATIO,NET_BUY,NET_BUY_RATIO,"
                                            + "TRADE_AMT,TRADE_RATIO,STR_YEAR,STR_MAI,ONLIST_TIMES,SECURITY_INNER_CODE", "UTF-8")
                                    + "&sortColumns=RANK&sortTypes=1"
                                    + "&pageSize=500&pageNumber=1"
                                    + "&source=WEB&client=WEB"
                                    + "&filter=" + URLEncoder.encode(filter, "UTF-8");
                        }
                    } catch (UnsupportedEncodingException e) {
                        throw new RuntimeException("UTF-8 编码失败", e);
                    }
                }
                case KAMT: {
                    // 北向资金 kamt.rtmin 端点：分钟级数据（s2n 南向、n2s 北向），纯 JSON（非 JSONP）
                    String d = (String) params.getOrDefault("tradeDate", "");
                    return "http://push2.eastmoney.com/api/qt/kamt.rtmin/get"
                            + "?fields1=f1,f2,f3,f4"
                            + "&fields2=f51,f52,f53,f54,f55,f56"
                            + "&ut=b2884a393a59ad64002292a3e90d46a5"
                            + "&date=" + d;
                }
                default:
                    throw new UnsupportedOperationException("buildUrl unsupported parserType for " + taskType);
            }
        }
    }

    // ========================================================================
    // 公共参数生成（cb / 时间戳）—— 所有 buildUrl 分支统一调用，避免散落各处
    // ========================================================================

    /**
     * 生成东财 JSONP 回调名：{@code jQuery + 随机数 + _ + 时间戳}。
     * <p>示例：{@code jQuery1124064700148312436964_1754288369534}。
     * 每次请求生成新值，避免 CDN 缓存命中同一响应。</p>
     */
    public static String generateCb() {
        return "jQuery" + new Random().nextLong() + "_" + System.currentTimeMillis();
    }

    /** 当前 13 位毫秒时间戳（东财 _ 参数，防缓存）。 */
    public static long generateTs() {
        return System.currentTimeMillis();
    }

    private static final Map<String, EndpointSpec> SPEC_BY_TYPE = new HashMap<>();

    static {
        // 涨停/跌停/炸板池（LIMIT_POOL 会被 SeedGenerator 展开成以下 3 个子任务）
        // 协议用 HTTP（与 stockDaily 一致）：HTTPS 经代理 CONNECT 隧道时 Proxy-Authorization 加不上会 407。
        SPEC_BY_TYPE.put("LIMIT_UP", new EndpointSpec(
                "LIMIT_UP", "http://push2ex.eastmoney.com/getTopicZTPool",
                ParserType.ZT_POOL, null, null, "limit_up"));
        SPEC_BY_TYPE.put("LIMIT_DOWN", new EndpointSpec(
                "LIMIT_DOWN", "http://push2ex.eastmoney.com/getTopicDTPool",
                ParserType.ZT_POOL, null, null, "limit_down"));
        SPEC_BY_TYPE.put("LIMIT_ZHABAN", new EndpointSpec(
                "LIMIT_ZHABAN", "http://push2ex.eastmoney.com/getTopicZBPool",
                ParserType.ZT_POOL, null, null, "zhaban"));
        // 兼容：LIMIT_POOL 直接作为 taskType 时也映射到涨停池（兜底）
        SPEC_BY_TYPE.put("LIMIT_POOL", new EndpointSpec(
                "LIMIT_POOL", "http://push2ex.eastmoney.com/getTopicZTPool",
                ParserType.ZT_POOL, null, null, "limit_up"));
        // 强势股池
        SPEC_BY_TYPE.put("STRONG_POOL", new EndpointSpec(
                "STRONG_POOL", "http://push2ex.eastmoney.com/getTopicQSPool",
                ParserType.ZT_POOL, null, null, "strong"));
        // 次新股池
        SPEC_BY_TYPE.put("CIXIN_POOL", new EndpointSpec(
                "CIXIN_POOL", "http://push2ex.eastmoney.com/getTopicCXPooll",
                ParserType.ZT_POOL, null, null, "cixin"));
        // 个股日线（push2 clist 全市场快照，按页拆任务；fs 含沪深主板+创业板+科创板）
        SPEC_BY_TYPE.put("STOCK_DAILY", new EndpointSpec(
                "STOCK_DAILY", "https://push2.eastmoney.com/api/qt/clist/get",
                ParserType.CLIST, "m:0+t:6,m:0+t:80,m:1+t:2,m:1+t:23", null, null));
        // 个股周线
        SPEC_BY_TYPE.put("STOCK_WEEKLY", new EndpointSpec(
                "STOCK_WEEKLY", "https://push2his.eastmoney.com/api/qt/stock/kline/get",
                ParserType.KLINE, null, null, null));
        // 指数日线（push2 clist 全市场指数快照，fs=b:MK0010 一次拿 43 只）
        SPEC_BY_TYPE.put("INDEX_DAILY", new EndpointSpec(
                "INDEX_DAILY", "http://push2.eastmoney.com/api/qt/clist/get",
                ParserType.CLIST, "b:MK0010", null, null));
        // 个股日K历史回填（push2his kline, klt=101 日线，lmt 设大一次拿满历史）
        SPEC_BY_TYPE.put("STOCK_DAILY_HISTORY", new EndpointSpec(
                "STOCK_DAILY_HISTORY", "https://push2his.eastmoney.com/api/qt/stock/kline/get",
                ParserType.KLINE, null, null, null));
        // 个股分钟K线（push2his kline, klt=1 分钟线，量价数据）
        SPEC_BY_TYPE.put("STOCK_KLINE_MINUTE", new EndpointSpec(
                "STOCK_KLINE_MINUTE", "https://push2his.eastmoney.com/api/qt/stock/kline/get",
                ParserType.KLINE, null, null, null));
        // 地域板块日线（board_type=1）
        SPEC_BY_TYPE.put("REGION_DAILY", new EndpointSpec(
                "REGION_DAILY", "http://push2.eastmoney.com/api/qt/clist/get",
                ParserType.CLIST, "m:90+t:1+f:!50", null, null));
        // 行业板块日线（board_type=2）
        SPEC_BY_TYPE.put("INDUSTRY_DAILY", new EndpointSpec(
                "INDUSTRY_DAILY", "http://push2.eastmoney.com/api/qt/clist/get",
                ParserType.CLIST, "m:90+t:2+f:!50", null, null));
        // 概念板块日线（board_type=3）
        SPEC_BY_TYPE.put("CONCEPT_DAILY", new EndpointSpec(
                "CONCEPT_DAILY", "http://push2.eastmoney.com/api/qt/clist/get",
                ParserType.CLIST, "m:90+t:3+f:!50", null, null));
        // 地域板块基础维表（board_basic，board_type=1）—— 独立抓取，fs 与 REGION_DAILY 一致
        SPEC_BY_TYPE.put("REGION_BOARD", new EndpointSpec(
                "REGION_BOARD", "http://push2.eastmoney.com/api/qt/clist/get",
                ParserType.CLIST, "m:90+t:1+f:!50", null, null));
        // 行业板块基础维表（board_basic，board_type=2）
        SPEC_BY_TYPE.put("INDUSTRY_BOARD", new EndpointSpec(
                "INDUSTRY_BOARD", "http://push2.eastmoney.com/api/qt/clist/get",
                ParserType.CLIST, "m:90+t:2+f:!50", null, null));
        // 概念板块基础维表（board_basic，board_type=3）
        SPEC_BY_TYPE.put("CONCEPT_BOARD", new EndpointSpec(
                "CONCEPT_BOARD", "http://push2.eastmoney.com/api/qt/clist/get",
                ParserType.CLIST, "m:90+t:3+f:!50", null, null));
        // 个股主力资金流
        SPEC_BY_TYPE.put("MAIN_FUND_STOCK", new EndpointSpec(
                "MAIN_FUND_STOCK", "https://push2.eastmoney.com/api/qt/clist/get",
                ParserType.CLIST, "m:0+t:6,m:0+t:80,m:1+t:2,m:1+t:23", null, null));
        // 板块主力资金流
        SPEC_BY_TYPE.put("MAIN_FUND_BOARD", new EndpointSpec(
                "MAIN_FUND_BOARD", "https://push2.eastmoney.com/api/qt/clist/get",
                ParserType.CLIST, "m:90+t:2,m:90+t:3,m:90+t:1", null, null));
        // 龙虎榜
        SPEC_BY_TYPE.put("DRAGON_TIGER", new EndpointSpec(
                "DRAGON_TIGER", "https://datacenter-web.eastmoney.com/api/data/get",
                ParserType.DATACENTER, null, "RPT_DAILYBILLBOARD_DETAILS", null));
        // 龙虎榜席位明细
        SPEC_BY_TYPE.put("DRAGON_TIGER_DETAIL", new EndpointSpec(
                "DRAGON_TIGER_DETAIL", "https://datacenter-web.eastmoney.com/api/data/get",
                ParserType.DATACENTER, null, "RPT_BILLBOARD_DETAIL", null));
                // 板块-个股关联（fs 动态拼 b:bk{boardCode}+f:!50，在 buildUrl 里处理）
        // 注意：STOCK_BY_BOARD 用 weblogin 路径（其他 CLIST 用 api 路径）
        SPEC_BY_TYPE.put("STOCK_BY_BOARD", new EndpointSpec(
                "STOCK_BY_BOARD", "https://push2.eastmoney.com/weblogin/api/qt/clist/get",
                ParserType.CLIST, null, null, null));
        // 北向资金（沪股通/深股通净买入）—— 东财 push2 kamt 实时端点（M6 接入，实测可用）
        // 响应结构：data.{hk2sh,hk2sz,sh2hk,sz2hk}.{netBuyAmt,date2}；北向净买入 = hk2sh + hk2sz
        SPEC_BY_TYPE.put("NORTHBOUND_FLOW", new EndpointSpec(
                "NORTHBOUND_FLOW", "https://push2.eastmoney.com/api/qt/kamt/get",
                ParserType.KAMT, null, null, null));
    }


    /**
     * 指数代码(东财 f12 裸码) → 后缀。后缀跟东财 secid market 走: 1→.SH 0→.SZ 2→.CSI。
     * <p>北交所指数(899050/899601)f13=0 归 .SZ。index_code 后续作东财接口入参,必须与东财一致。</p>
     */
    private static final Map<String, String> INDEX_CODE_SUFFIX = new HashMap<>();

    static {
        // f13=1 → .SH（沪市）
        INDEX_CODE_SUFFIX.put("000001", ".SH"); // 上证指数
        INDEX_CODE_SUFFIX.put("000300", ".SH"); // 沪深300
        INDEX_CODE_SUFFIX.put("000016", ".SH"); // 上证50
        INDEX_CODE_SUFFIX.put("000888", ".SH"); // 上证综合全收益
        INDEX_CODE_SUFFIX.put("000680", ".SH"); // 科创综指
        INDEX_CODE_SUFFIX.put("000688", ".SH"); // 科创50
        INDEX_CODE_SUFFIX.put("000903", ".SH"); // 中证A100
        INDEX_CODE_SUFFIX.put("000510", ".SH"); // 中证A500
        INDEX_CODE_SUFFIX.put("000904", ".SH"); // 中证200
        INDEX_CODE_SUFFIX.put("000905", ".SH"); // 中证500
        INDEX_CODE_SUFFIX.put("000906", ".SH"); // 中证800
        INDEX_CODE_SUFFIX.put("000852", ".SH"); // 中证1000
        INDEX_CODE_SUFFIX.put("000985", ".SH"); // 中证全指
        INDEX_CODE_SUFFIX.put("000010", ".SH"); // 上证180
        INDEX_CODE_SUFFIX.put("000009", ".SH"); // 上证380
        INDEX_CODE_SUFFIX.put("000132", ".SH"); // 上证100
        INDEX_CODE_SUFFIX.put("000133", ".SH"); // 上证150
        INDEX_CODE_SUFFIX.put("000003", ".SH"); // Ｂ股指数
        INDEX_CODE_SUFFIX.put("000012", ".SH"); // 国债指数
        INDEX_CODE_SUFFIX.put("000013", ".SH"); // 企债指数
        INDEX_CODE_SUFFIX.put("000011", ".SH"); // 基金指数
        // f13=0 → .SZ（深市/创业板/北交所）
        INDEX_CODE_SUFFIX.put("399001", ".SZ"); // 深证成指
        INDEX_CODE_SUFFIX.put("399006", ".SZ"); // 创业板指
        INDEX_CODE_SUFFIX.put("899050", ".SZ"); // 北证50
        INDEX_CODE_SUFFIX.put("399330", ".SZ"); // 深证100
        INDEX_CODE_SUFFIX.put("399673", ".SZ"); // 创业板50
        INDEX_CODE_SUFFIX.put("399750", ".SZ"); // 深主板50
        INDEX_CODE_SUFFIX.put("899601", ".SZ"); // 北证专精特新
        INDEX_CODE_SUFFIX.put("399002", ".SZ"); // 深成指R
        INDEX_CODE_SUFFIX.put("399850", ".SZ"); // 深证50
        INDEX_CODE_SUFFIX.put("399005", ".SZ"); // 中小100
        INDEX_CODE_SUFFIX.put("399003", ".SZ"); // 成份Ｂ指
        INDEX_CODE_SUFFIX.put("399106", ".SZ"); // 深证综指
        INDEX_CODE_SUFFIX.put("399004", ".SZ"); // 深证100R
        INDEX_CODE_SUFFIX.put("399007", ".SZ"); // 深证300
        INDEX_CODE_SUFFIX.put("399008", ".SZ"); // 中小300
        INDEX_CODE_SUFFIX.put("399293", ".SZ"); // 创业大盘
        INDEX_CODE_SUFFIX.put("399019", ".SZ"); // 创业200
        INDEX_CODE_SUFFIX.put("399020", ".SZ"); // 创业500
        INDEX_CODE_SUFFIX.put("399100", ".SZ"); // 新指数
        INDEX_CODE_SUFFIX.put("399550", ".SZ"); // 央视50
        // f13=2 → .CSI（中证系）
        INDEX_CODE_SUFFIX.put("930050", ".CSI"); // 中证A50
        INDEX_CODE_SUFFIX.put("932000", ".CSI"); // 中证2000
    }

    private static final Map<String, String> INDEX_NAMES = new HashMap<>();

    static {
        INDEX_NAMES.put("000001", "上证指数");
        INDEX_NAMES.put("399001", "深证成指");
        INDEX_NAMES.put("899050", "北证50");
        INDEX_NAMES.put("399006", "创业板指");
        INDEX_NAMES.put("000680", "科创综指");
        INDEX_NAMES.put("000688", "科创50");
        INDEX_NAMES.put("399330", "深证100");
        INDEX_NAMES.put("000300", "沪深300");
        INDEX_NAMES.put("000016", "上证50");
        INDEX_NAMES.put("399673", "创业板50");
        INDEX_NAMES.put("000888", "上证综合全收益");
        INDEX_NAMES.put("399750", "深主板50");
        INDEX_NAMES.put("899601", "北证专精特新");
        INDEX_NAMES.put("930050", "中证A50");
        INDEX_NAMES.put("000903", "中证A100");
        INDEX_NAMES.put("000510", "中证A500");
        INDEX_NAMES.put("000904", "中证200");
        INDEX_NAMES.put("000905", "中证500");
        INDEX_NAMES.put("000906", "中证800");
        INDEX_NAMES.put("000852", "中证1000");
        INDEX_NAMES.put("932000", "中证2000");
        INDEX_NAMES.put("000985", "中证全指");
        INDEX_NAMES.put("000010", "上证180");
        INDEX_NAMES.put("000009", "上证380");
        INDEX_NAMES.put("000132", "上证100");
        INDEX_NAMES.put("000133", "上证150");
        INDEX_NAMES.put("000003", "Ｂ股指数");
        INDEX_NAMES.put("000012", "国债指数");
        INDEX_NAMES.put("000013", "企债指数");
        INDEX_NAMES.put("000011", "基金指数");
        INDEX_NAMES.put("399002", "深成指R");
        INDEX_NAMES.put("399850", "深证50");
        INDEX_NAMES.put("399005", "中小100");
        INDEX_NAMES.put("399003", "成份Ｂ指");
        INDEX_NAMES.put("399106", "深证综指");
        INDEX_NAMES.put("399004", "深证100R");
        INDEX_NAMES.put("399007", "深证300");
        INDEX_NAMES.put("399008", "中小300");
        INDEX_NAMES.put("399293", "创业大盘");
        INDEX_NAMES.put("399019", "创业200");
        INDEX_NAMES.put("399020", "创业500");
        INDEX_NAMES.put("399100", "新指数");
        INDEX_NAMES.put("399550", "央视50");
    }

    public static EndpointSpec get(String taskType) {
        EndpointSpec spec = SPEC_BY_TYPE.get(taskType);
        if (spec == null) {
            throw new UnsupportedOperationException(
                    "Eastmoney endpoint NOT implemented for taskType=" + taskType + " TODO M2");
        }
        return spec;
    }

    /** CLIST 各类默认的 fields 投影。 */
    static String defaultFields(String taskType) {
        switch (taskType) {
            case "REGION_DAILY":
            case "INDUSTRY_DAILY":
            case "CONCEPT_DAILY":
                // 板块行情投影（含行情明细 + 领涨股）
                return "f12,f14,f2,f3,f4,f5,f6,f7,f8,f10,f15,f16,f17,f18,f20,f21,f62,f104,f105,f140,f128";
            case "MAIN_FUND_STOCK":
                // 含 f13 以便补 ts_code 后缀
                return "f12,f13,f14,f62,f66,f72,f78,f84";
            case "MAIN_FUND_BOARD":
                return "f12,f14,f62,f66,f72,f78,f84";
            case "STOCK_DAILY":
                // 全市场快照完整投影（f1-f173，含行情/市值/封板/涨停类型等）
                return "f1,f2,f3,f4,f5,f6,f7,f8,f9,f10,f11,f12,f13,f14,f15,f16,f17,f18,f20,f21,f23,f24,f25,f62,f115,f128,f140,f141,f136,f152,f173";
            case "STOCK_BY_BOARD":
                // f12=股票代码, f14=股票名称, f2=权重, f3=类型标识
                return "f12,f14,f2,f3";
            case "INDEX_DAILY":
                // 全市场指数快照完整投影(含行情/状态,实测 2026-08-11)
                return "f12,f13,f14,f1,f2,f4,f152,f5,f6,f18,f17,f15,f16";
            default:
                return "f12,f14,f3,f62";
        }
    }

    /** 从 params 解析交易日（tradeDate 或 date），缺失返回 null。 */
    static String tradeDate(Map<String, Object> params) {
        Object v = params.get("tradeDate");
        if (v == null) {
            v = params.get("date");
        }
        return v == null ? null : String.valueOf(v);
    }

    static String requireTradeDate(Map<String, Object> params) {
        String td = tradeDate(params);
        if (td == null || td.isBlank()) {
            throw new RuntimeException("params 缺少 tradeDate/date（分区键必需）");
        }
        return td;
    }

    /** 解析 secid（需 taskType 上下文）。 */
    static String secidFor(String taskType, Map<String, Object> params) {
        String ts = String.valueOf(params.get("tsCode"));
        String market;
        if (ts.endsWith(".SH")) {
            market = "1";
        } else if (ts.endsWith(".SZ")) {
            market = "0";
        } else if (ts.endsWith(".CSI")) {
            market = "1";
        } else {
            // 无后缀：优先读 params 中的 isHs（market 项目逻辑：0=深市 1=沪市）
            Object isHs = params.get("isHs");
            if (isHs != null) {
                market = String.valueOf(isHs);
            } else {
                // 兜底：按代码前缀判断
                String code = ts.contains(".") ? ts.substring(0, ts.indexOf('.')) : ts;
                if (code.startsWith("6") || code.startsWith("11")) {
                    market = "1"; // 沪市（60xxxx 主板 / 11xxxx 科创板）
                } else if (code.startsWith("0") || code.startsWith("3") || code.startsWith("12") || code.startsWith("13")) {
                    market = "0"; // 深市（00xxxx 主板 / 30xxxx 创业板 / 12xxxx 可转债 / 13xxxx）
                } else if (code.startsWith("8") || code.startsWith("4")) {
                    market = "0"; // 北交所
                } else {
                    market = "1"; // 兜底
                }
            }
        }
        String code = ts.contains(".") ? ts.substring(0, ts.indexOf('.')) : ts;
        return market + "." + code;
    }

    static String indexName(String indexCode) {
        if (indexCode == null) {
            return null;
        }
        String raw = indexCode.contains(".") ? indexCode.substring(0, indexCode.indexOf('.')) : indexCode;
        return INDEX_NAMES.get(raw);
    }

    /** 东财 f12 裸码 + f13 市场码 → 带后缀指数代码(后缀跟东财 secid market 走)。 */
    static String indexCodeFor(String f12Raw, String f13) {
        if (f12Raw == null || f12Raw.isEmpty() || "-".equals(f12Raw)) {
            return null;
        }
        String suffix;
        if ("1".equals(f13)) {
            suffix = ".SH";
        } else if ("0".equals(f13)) {
            suffix = ".SZ";
        } else {
            suffix = ".CSI"; // f13=2 中证系(930050/932000)
        }
        return f12Raw + suffix;
    }

    /** Object → int，解析失败返回 fallback。 */
    static int parseInt(Object fallback, int val) {
        if (fallback instanceof Number n) return n.intValue();
        String s = String.valueOf(fallback).trim();
        if (s.isEmpty() || "-".equals(s)) return val;
        try { return Integer.parseInt(s); } catch (NumberFormatException e) { return val; }
    }
}
