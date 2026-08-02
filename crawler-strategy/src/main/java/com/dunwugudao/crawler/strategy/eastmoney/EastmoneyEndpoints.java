package com.dunwugudao.crawler.strategy.eastmoney;

import java.util.HashMap;
import java.util.Map;

/**
 * 按 taskType 路由的东财端点表（M2 深化）。
 * <p>每个 {@link EndpointSpec} 描述：baseUrl、是否需要 secid(kline 类)、字段投影、解析器类型、
 * 以及构建 URL 的方法。未实现的 taskType 在 {@link #get(String)} 抛
 * {@link UnsupportedOperationException} 并留 TODO。</p>
 *
 * <p>覆盖：LIMIT_POOL / STOCK_DAILY / STOCK_WEEKLY / INDEX_DAILY / BOARD_DAILY /
 * MAIN_FUND_STOCK / MAIN_FUND_BOARD / DRAGON_TIGER / DRAGON_TIGER_DETAIL。</p>
 */
public final class EastmoneyEndpoints {

    public enum ParserType {
        CLIST,      // push2 clist（板块/资金流分页）
        KLINE,      // push2his kline（个股/指数日周线）
        ZT_POOL,    // push2ex 涨停/跌停/炸板池
        DATACENTER  // datacenter-web 龙虎榜
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
                    String lt = String.valueOf(params.getOrDefault("limitType", "limit_up"));
                    String path;
                    if ("limit_down".equals(lt)) {
                        path = "getTopicDTPool";
                    } else if ("zhaban".equals(lt)) {
                        path = "getTopicZBPool";
                    } else if ("strong".equals(lt)) {
                        path = "getTopicQSPool";
                    } else if ("cixin".equals(lt)) {
                        path = "getTopicCXPooll";
                    } else {
                        path = "getTopicZTPool";
                    }
                    // 涨停/跌停/强势/次新池：一次取全量（pagesize=5000），无需分页
                    // 实测能工作的 URL（2026-08-01）
                    // 源：https://push2ex.eastmoney.com/getTopicZTPool?cb=callbackdata6233583&ut=...&dpt=wz.ztzt&Pageindex=0&pagesize=17000&sort=fbt%3Aasc&date=20260801&_=1785562460759
                    String d = tradeDate(params).replace("-", "");
                    long ts = System.currentTimeMillis();
                    return "https://push2ex.eastmoney.com/" + path
                            + "?cb=callbackdata6233583&ut=7eea3edcaed734bea9cbfc24409ed989&dpt=wz.ztzt"
                            + "&date=" + d + "&Pageindex=0&pagesize=5000&sort=fbt%3Aasc&_=" + ts;
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
                    return baseUrl
                            + "?pn=" + page + "&pz=100&po=1&np=1&fltt=2&invt=2"
                            + "&fs=" + fsVal + "&fields=" + fields;
                }
                case DATACENTER: {
                    String td = tradeDate(params);
                    if ("DRAGON_TIGER".equals(taskType)) {
                        return baseUrl + "?type=" + dcType
                                + "&filter=(TRADE_DATE%3D%27" + td + "%27)"
                                + "&page_size=1000&pz=1000&po=1&fields1=f1&fields2=f2,f3,f4,f5,f6,f7";
                    } else { // DRAGON_TIGER_DETAIL
                        String code = String.valueOf(params.get("code"));
                        return baseUrl + "?type=" + dcType
                                + "&filter=(TRADE_DATE%3D%27" + td + "%27)(SECURITY_CODE%3D%27" + code + "%27)"
                                + "&page_size=1000&pz=1000";
                    }
                }
                default:
                    throw new UnsupportedOperationException("buildUrl unsupported parserType for " + taskType);
            }
        }
    }

    private static final Map<String, EndpointSpec> SPEC_BY_TYPE = new HashMap<>();

    static {
        // 涨停/跌停/炸板池（LIMIT_POOL 会被 SeedGenerator 展开成以下 3 个子任务）
        SPEC_BY_TYPE.put("LIMIT_UP", new EndpointSpec(
                "LIMIT_UP", "https://push2ex.eastmoney.com/getTopicZTPool",
                ParserType.ZT_POOL, null, null, "limit_up"));
        SPEC_BY_TYPE.put("LIMIT_DOWN", new EndpointSpec(
                "LIMIT_DOWN", "https://push2ex.eastmoney.com/getTopicDTPool",
                ParserType.ZT_POOL, null, null, "limit_down"));
        SPEC_BY_TYPE.put("LIMIT_ZHABAN", new EndpointSpec(
                "LIMIT_ZHABAN", "https://push2ex.eastmoney.com/getTopicZBPool",
                ParserType.ZT_POOL, null, null, "zhaban"));
        // 兼容：LIMIT_POOL 直接作为 taskType 时也映射到涨停池（兜底）
        SPEC_BY_TYPE.put("LIMIT_POOL", new EndpointSpec(
                "LIMIT_POOL", "https://push2ex.eastmoney.com/getTopicZTPool",
                ParserType.ZT_POOL, null, null, "limit_up"));
        // 强势股池
        SPEC_BY_TYPE.put("STRONG_POOL", new EndpointSpec(
                "STRONG_POOL", "https://push2ex.eastmoney.com/getTopicQSPool",
                ParserType.ZT_POOL, null, null, "strong"));
        // 次新股池
        SPEC_BY_TYPE.put("CIXIN_POOL", new EndpointSpec(
                "CIXIN_POOL", "https://push2ex.eastmoney.com/getTopicCXPooll",
                ParserType.ZT_POOL, null, null, "cixin"));
        // 个股日线
        SPEC_BY_TYPE.put("STOCK_DAILY", new EndpointSpec(
                "STOCK_DAILY", "https://push2his.eastmoney.com/api/qt/stock/kline/get",
                ParserType.KLINE, null, null, null));
        // 个股周线
        SPEC_BY_TYPE.put("STOCK_WEEKLY", new EndpointSpec(
                "STOCK_WEEKLY", "https://push2his.eastmoney.com/api/qt/stock/kline/get",
                ParserType.KLINE, null, null, null));
        // 指数日线
        SPEC_BY_TYPE.put("INDEX_DAILY", new EndpointSpec(
                "INDEX_DAILY", "https://push2his.eastmoney.com/api/qt/stock/kline/get",
                ParserType.KLINE, null, null, null));
        // 板块日线（行业+概念+地域）
        SPEC_BY_TYPE.put("BOARD_DAILY", new EndpointSpec(
                "BOARD_DAILY", "https://push2.eastmoney.com/api/qt/clist/get",
                ParserType.CLIST, "m:90+t:2,m:90+t:3,m:90+t:1", null, null));
        // 地域板块日线（board_type=1）
        SPEC_BY_TYPE.put("REGION_DAILY", new EndpointSpec(
                "REGION_DAILY", "https://push2.eastmoney.com/api/qt/clist/get",
                ParserType.CLIST, "m:90+t:1+f:!50", null, null));
        // 行业板块日线（board_type=2）
        SPEC_BY_TYPE.put("INDUSTRY_DAILY", new EndpointSpec(
                "INDUSTRY_DAILY", "https://push2.eastmoney.com/api/qt/clist/get",
                ParserType.CLIST, "m:90+t:2+f:!50", null, null));
        // 概念板块日线（board_type=3）
        SPEC_BY_TYPE.put("CONCEPT_DAILY", new EndpointSpec(
                "CONCEPT_DAILY", "https://push2.eastmoney.com/api/qt/clist/get",
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
        SPEC_BY_TYPE.put("STOCK_BY_BOARD", new EndpointSpec(
                "STOCK_BY_BOARD", "https://push2.eastmoney.com/api/qt/clist/get",
                ParserType.CLIST, null, null, null));
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
            case "BOARD_DAILY":
                // 含行情明细：f2 价格/f4 涨跌额/f5 成交量/f6 成交额/f7 振幅/f8 换手/f10 量比
                // f15 最高/f16 最低/f17 今开/f18 昨收/f20 总市值/f21 流通市值
                // f166 领涨股代码 / f167 领涨股名称
                return "f12,f14,f2,f3,f4,f5,f6,f7,f8,f10,f15,f16,f17,f18,f20,f21,f62,f104,f105,f166,f167";
            case "REGION_DAILY":
            case "INDUSTRY_DAILY":
            case "CONCEPT_DAILY":
                // 单类型板块行情投影（和 BOARD_DAILY 一致，含领涨股）
                return "f12,f14,f2,f3,f4,f5,f6,f7,f8,f10,f15,f16,f17,f18,f20,f21,f62,f104,f105,f166,f167";
            case "MAIN_FUND_STOCK":
                // 含 f13 以便补 ts_code 后缀
                return "f12,f13,f14,f62,f66,f72,f78,f84";
            case "MAIN_FUND_BOARD":
                return "f12,f14,f62,f66,f72,f78,f84";
            case "STOCK_BY_BOARD":
                // f12=股票代码, f13=市场码(补后缀), f14=股票名称
                return "f12,f13,f14";
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
}
