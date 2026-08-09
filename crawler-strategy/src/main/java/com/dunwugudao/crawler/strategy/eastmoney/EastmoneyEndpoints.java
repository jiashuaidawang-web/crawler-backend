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
                    // 涨停/跌停/强势/次新池：按页拆任务，Pageindex/pagesize 由种子注入（Pageindex 从 0 开始，每页 100 条）
                    // 排序：涨停/炸板 fbt asc；跌停用 fund asc；强势/次新用 zdp desc（用户实测 2026-08-02/03）
                    String d = tradeDate(params).replace("-", "");
                    long ts = generateTs();
                    int pageindex = parseInt(params.getOrDefault("Pageindex", 0), 0);
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
                    String klt = "STOCK_WEEKLY".equals(taskType) ? "102" : "101";
                    String date = String.valueOf(params.getOrDefault("date", params.getOrDefault("tradeDate", "")));
                    String lmt = String.valueOf(params.getOrDefault("lmt", "1"));
                    return baseUrl
                            + "?secid=" + secid + "&klt=" + klt + "&fqt=0"
                            + "&fields1=f1,f2,f3,f4,f5,f6"
                            + "&fields2=f51,f52,f53,f54,f55,f56,f57,f58,f59,f60,f61"
                            + "&end=" + date + "&lmt=" + lmt;
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
                    return "http://83.push2.eastmoney.com/api/qt/clist/get"
                            + "?cb=" + cb
                            + "&pn=" + pn + "&pz=100&po=1&np=1"
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
                                    + "SELL_SEAT_NEW,BUY_RATIO,SELL_RATIO,CHANGE_TYPE";
                            String filter = "(TRADE_DATE<='" + tdDash + "')(TRADE_DATE>='" + tdDash + "')";
                            return "https://datacenter-web.eastmoney.com/api/data/v1/get"
                                    + "?reportName=RPT_DAILYBILLBOARD_DETAILSNEW"
                                    + "&columns=" + URLEncoder.encode(columns, "UTF-8")
                                    + "&sortColumns=SECURITY_CODE,TRADE_DATE&sortTypes=1,-1"
                                    + "&pageSize=5000&pageNumber=1"
                                    + "&source=WEB&client=WEB"
                                    + "&filter=" + URLEncoder.encode(filter, "UTF-8");
                        } else { // DRAGON_TIGER_DETAIL —— 同样切新版；reportName/列名待单独验证（M6）
                            String code = String.valueOf(params.get("code"));
                            String filter = "(TRADE_DATE<='" + tdDash + "')(TRADE_DATE>='" + tdDash
                                    + "')(SECURITY_CODE='" + code + "')";
                            return "https://datacenter-web.eastmoney.com/api/data/v1/get"
                                    + "?reportName=RPT_BILLBOARD_DETAIL"
                                    + "&columns=" + URLEncoder.encode(
                                            "SEAT_NAME,SEAT_TYPE,BUY,SELL,TRADE_DATE,SECURITY_CODE", "UTF-8")
                                    + "&sortColumns=TRADE_DATE&sortTypes=-1"
                                    + "&pageSize=5000&pageNumber=1"
                                    + "&source=WEB&client=WEB"
                                    + "&filter=" + URLEncoder.encode(filter, "UTF-8");
                        }
                    } catch (UnsupportedEncodingException e) {
                        throw new RuntimeException("UTF-8 编码失败", e);
                    }
                }
                case KAMT: {
                    // 北向资金 kamt 端点：纯 JSON（非 JSONP），无需 cb / _ 时间戳
                    String fields1 = "f1,f2,f3,f4";
                    String fields2 = "f51,f52,f53,f54,f55,f56,f57,f58,f59,f60,f61,f62,f63,f64,f65,f66,f67,f68,f69,f70,f71,f72,f73,f74,f75,f76,f77,f78,f79,f80";
                    return baseUrl
                            + "?fields1=" + fields1
                            + "&fields2=" + fields2
                            + "&ut=b2884a393a59ad64002292a3e90d46a5";
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
        // 指数日线
        SPEC_BY_TYPE.put("INDEX_DAILY", new EndpointSpec(
                "INDEX_DAILY", "https://push2his.eastmoney.com/api/qt/stock/kline/get",
                ParserType.KLINE, null, null, null));
        // 个股日K历史回填（push2his kline, klt=101 日线，lmt 设大一次拿满历史）
        SPEC_BY_TYPE.put("STOCK_DAILY_HISTORY", new EndpointSpec(
                "STOCK_DAILY_HISTORY", "https://push2his.eastmoney.com/api/qt/stock/kline/get",
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

    private static final Map<String, String> INDEX_SECIDS = new HashMap<>();

    static {
        // 指数代码 → 东财 secid（market.code）
        INDEX_SECIDS.put("000001.SH", "1.000001");
        INDEX_SECIDS.put("399001.SZ", "0.399001");
        INDEX_SECIDS.put("399006.SZ", "0.399006");
        INDEX_SECIDS.put("000300.SH", "1.000300");
        INDEX_SECIDS.put("000905.SH", "1.000905");
        INDEX_SECIDS.put("000852.SH", "1.000852");
        INDEX_SECIDS.put("932000.CSI", "1.932000");
    }

    private static final Map<String, String> INDEX_NAMES = new HashMap<>();

    static {
        INDEX_NAMES.put("000001.SH", "上证综指");
        INDEX_NAMES.put("399001.SZ", "深证成指");
        INDEX_NAMES.put("399006.SZ", "创业板指");
        INDEX_NAMES.put("000300.SH", "沪深300");
        INDEX_NAMES.put("000905.SH", "中证500");
        INDEX_NAMES.put("000852.SH", "中证1000");
        INDEX_NAMES.put("932000.CSI", "中证2000");
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
        if ("INDEX_DAILY".equals(taskType)) {
            String idx = String.valueOf(params.get("indexCode"));
            String s = INDEX_SECIDS.get(idx);
            if (s == null) {
                throw new RuntimeException("Eastmoney 无该指数 secid 映射: indexCode=" + idx
                        + " TODO M6 补充指数映射");
            }
            return s;
        }
        String ts = String.valueOf(params.get("tsCode")); // 形如 600000.SH
        String market;
        if (ts.endsWith(".SH")) {
            market = "1";
        } else if (ts.endsWith(".SZ")) {
            market = "0";
        } else if (ts.endsWith(".CSI")) {
            market = "1";
        } else {
            market = "1"; // 兜底，真实场景应由 seed 保证后缀
        }
        String code = ts.contains(".") ? ts.substring(0, ts.indexOf('.')) : ts;
        return market + "." + code;
    }

    static String indexName(String indexCode) {
        return INDEX_NAMES.get(indexCode);
    }

    /** Object → int，解析失败返回 fallback。 */
    static int parseInt(Object fallback, int val) {
        if (fallback instanceof Number n) return n.intValue();
        String s = String.valueOf(fallback).trim();
        if (s.isEmpty() || "-".equals(s)) return val;
        try { return Integer.parseInt(s); } catch (NumberFormatException e) { return val; }
    }
}
