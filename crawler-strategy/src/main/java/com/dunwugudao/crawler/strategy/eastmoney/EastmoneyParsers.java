package com.dunwugudao.crawler.strategy.eastmoney;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.MissingNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 东财响应解析器（OkHttp / Playwright 策略共用）。
 * <p>按 taskType 分支，把 JsonNode 解析为 {@code List<Map<String,Object>>}
 * （key=schema 列名，必带 trade_date）。</p>
 */
public final class EastmoneyParsers {

    private static final Logger log = LoggerFactory.getLogger(EastmoneyParsers.class);

    private EastmoneyParsers() {
    }

    /** CLIST 解析器（板块行情 / 资金流 / 全市场快照）。 */
    public static List<Map<String, Object>> parseClist(JsonNode data, EastmoneyEndpoints.EndpointSpec spec,
                                                       String tradeDate, Map<String, Object> params) {
        List<Map<String, Object>> rows = new ArrayList<>();
        JsonNode diff = data.path("diff");
        if (!diff.isArray()) {
            return rows;
        }
        int nullOpenCount = 0;
        int nullCloseCount = 0;
        for (JsonNode n : diff) {
            Map<String, Object> row = new HashMap<>();
            switch (spec.getTaskType()) {
                case "REGION_DAILY":
                case "INDUSTRY_DAILY":
                case "CONCEPT_DAILY":
                    row.put("board_code", txt(n, "f12"));
                    row.put("board_name", txt(n, "f14"));
                    // board_type 来自 taskType 映射（响应无区分地域/行业/概念的字段）
                    row.put("board_type", taskTypeToBoardType(spec.getTaskType()));
                    row.put("pct_chg", num(n, "f3"));
                    row.put("main_net", num(n, "f62"));
                    row.put("price", num(n, "f2"));
                    row.put("rise_fall", num(n, "f4"));
                    row.put("volume", num(n, "f5"));
                    row.put("amplitude", num(n, "f7"));
                    row.put("high_price", num(n, "f15"));
                    row.put("low_price", num(n, "f16"));
                    row.put("today_open_price", num(n, "f17"));
                    row.put("yesterday_received_price", num(n, "f18"));
                    row.put("volume_ratio", num(n, "f10"));
                    row.put("turnover_ratio", num(n, "f8"));
                    row.put("total_market_value", num(n, "f20"));
                    row.put("circulation_market_value", num(n, "f21"));
                    row.put("amount", num(n, "f6"));
                    row.put("up_count", toInt(num(n, "f104")));
                    row.put("down_count", toInt(num(n, "f105")));
                    row.put("leading_code", txt(n, "f140"));
                    row.put("leading_name", txt(n, "f128"));
                    // TODO M6: limit_up_count / board_code2 东财 clist 无直接字段，置 NULL
                    row.put("limit_up_count", null);
                    row.put("board_code2", null);
                    break;
                // 板块基础维表（board_basic）—— 仅取 3 字段，board_type 由 taskType 映射
                case "REGION_BOARD":
                case "INDUSTRY_BOARD":
                case "CONCEPT_BOARD":
                    row.put("board_code", txt(n, "f12"));
                    row.put("board_name", txt(n, "f14"));
                    row.put("board_type", taskTypeToBoardType(spec.getTaskType()));
                    break;
                case "MAIN_FUND_STOCK":
                    row.put("obj_type", "stock");
                    row.put("ts_code", EastmoneyFieldMap.toTsCode(txt(n, "f12"), txt(n, "f13")));
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
                    row.put("ts_code", "0");
                    row.put("index_code", "0");
                    row.put("main_net", num(n, "f62"));
                    row.put("super_big", num(n, "f66"));
                    row.put("big_net", num(n, "f72"));
                    row.put("mid_net", num(n, "f78"));
                    row.put("small_net", num(n, "f84"));
                    break;
                case "STOCK_BY_BOARD":
                    // board_code/board_name/board_type 来自任务上下文（params），不读响应
                    row.put("board_code", String.valueOf(params.getOrDefault("boardCode", "")));
                    row.put("board_name", String.valueOf(params.getOrDefault("boardName", "")));
                    row.put("board_type", parseInt(params.get("boardType")));
                    // ts_code 直接取 f12（接口返回的就是带后缀的代码，如 600000.SH）
                    row.put("ts_code", txt(n, "f12"));
                    row.put("stock_name", txt(n, "f14"));          // f14 股票名称
                    row.put("weight", num(n, "f2"));              // f2 权重
                    row.put("is_leader", null);                  // TODO M6
                    row.put("is_midarm", null);                  // TODO M6
                    break;
                case "INDEX_DAILY":
                    // 全市场指数快照（push2 clist, fs=b:MK0010, 43 只一次拿完）
                    // clist 返回的价格已是元(非分),不除 100
                    Double closeVal = num(n, "f2");
                    Double preCloseVal = num(n, "f18");
                    row.put("sec_type", toInt(num(n, "f1")));                 // f1  证券类型(2=指数)
                    row.put("index_code", indexCodeFor(txt(n, "f12"), txt(n, "f13")));
                    row.put("index_name", txt(n, "f14"));
                    row.put("close", closeVal);                              // f2  收盘价(元)
                    row.put("open", num(n, "f17"));                          // f17 开盘价(元)
                    row.put("high", num(n, "f15"));                          // f15 最高价(元)
                    row.put("low", num(n, "f16"));                           // f16 最低价(元)
                    row.put("pre_close", preCloseVal);                       // f18 昨收价(元)
                    // 涨跌幅自己算:(close - pre_close) / pre_close * 100
                    Double pctChg = null;
                    if (closeVal != null && preCloseVal != null && preCloseVal != 0) {
                        pctChg = (closeVal - preCloseVal) / preCloseVal * 100;
                    }
                    row.put("pct_chg", pctChg);
                    // 涨跌额:直接取 f4(元,不除 100)
                    row.put("change_amt", num(n, "f4"));
                    row.put("vol", num(n, "f5"));                            // f5  成交量(股)
                    row.put("amount", num(n, "f6"));                         // f6  成交额(元)
                    row.put("data_status", dataStatusText(txt(n, "f152")));  // f152 数据状态
                    break;
                case "STOCK_DAILY":
                    // 全市场快照（push2 clist）：18 字段精简版(用户终稿 2026-08-04)
                    // 价格类接口单位已是元,直接取(不÷100)
                    row.put("ts_code", EastmoneyFieldMap.toTsCode(txt(n, "f12"), txt(n, "f13")));
                    row.put("stock_name", txt(n, "f14"));
                    row.put("close", num(n, "f2"));           // f2  收盘价(元)
                    row.put("pct_chg", num(n, "f3"));        // f3  涨跌幅%
                    row.put("chg_amount", num(n, "f4"));     // f4  涨跌额(元)
                    row.put("vol", num(n, "f5"));            // f5  成交量(手)
                    row.put("amount", num(n, "f6"));         // f6  成交额(元)
                    row.put("amplitude", num(n, "f7"));      // f7  振幅%
                    row.put("turnover", num(n, "f8"));       // f8  换手率%
                    row.put("pe", num(n, "f9"));             // f9  市盈率(TTM)
                    row.put("volume_ratio", num(n, "f10"));  // f10 量比
                    row.put("high", num(n, "f15"));          // f15 最高价(元)
                    row.put("low", num(n, "f16"));           // f16 最低价(元)
                    row.put("open", num(n, "f17"));          // f17 开盘价(元)
                    row.put("pre_close", num(n, "f18"));     // f18 昨收(元)
                    row.put("total_mv", num(n, "f20"));      // f20 总市值(元)
                    row.put("circ_mv", num(n, "f21"));       // f21 流通市值(元)
                    row.put("chg_60d", num(n, "f23"));       // f23 60日涨跌幅%
                    row.put("market_code", num(n, "f152"));  // f152 市场码(0深/1沪/2京)
                    row.put("main_net", num(n, "f62"));      // f62 主力净流入
                    row.put("super_big", num(n, "f66"));     // f66 超大单净流入
                    row.put("big_net", num(n, "f72"));       // f72 大单净流入
                    row.put("mid_net", num(n, "f78"));       // f78 中单净流入
                    row.put("small_net", num(n, "f84"));     // f84 小单净流入
                    row.put("pe_static", num(n, "f115"));    // f115 静态市盈率
                    row.put("velocity", num(n, "f11"));       // f11 涨速%
                    row.put("turn_speed", num(n, "f22"));     // f22 涨速(另一口径)
                    row.put("reserved_f24", num(n, "f24"));   // f24 年初至今涨跌幅
                    row.put("reserved_f25", num(n, "f25"));   // f25 待确认
                    row.put("reserved_f173", num(n, "f173")); // f173 涨速%
                    // 涨跌停标记（pct_chg 近似判定，创业板/科创板 20% 与主板 10% 统一阈值，TODO M6 精确）
                    Double pct = num(n, "f3");
                    row.put("is_limit_up", (pct != null && pct >= 9.8) ? 1 : 0);
                    row.put("is_limit_down", (pct != null && pct <= -9.8) ? 1 : 0);
                    // DEBUG: 统计 open/close 为 null 的比例
                    if (row.get("open") == null) nullOpenCount++;
                    if (row.get("close") == null) nullCloseCount++;
                    // DEBUG: 每 100 行打一条样本（含原始 f 码）
                    if (rows.size() % 100 == 0) {
                        log.debug("[STOCK_DAILY] sample row #{}: f12={}, f14={}, f2={}, f15={}, f16={}, f17={}, f18={}, raw_f17='{}', raw_f15='{}'",
                                rows.size(), txt(n, "f12"), txt(n, "f14"), txt(n, "f2"),
                                txt(n, "f15"), txt(n, "f16"), txt(n, "f17"), txt(n, "f18"),
                                n.get("f17"), n.get("f15"));
                    }
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
        // DEBUG: STOCK_DAILY 解析汇总
        if ("STOCK_DAILY".equals(spec.getTaskType())) {
            log.debug("[STOCK_DAILY] parse done: totalRows={}, nullOpen={}, nullClose={}", rows.size(), nullOpenCount, nullCloseCount);
        }
        return rows;
    }

    /** KLINE 解析器（个股/指数日周线）。 */
    public static List<Map<String, Object>> parseKline(JsonNode data, EastmoneyEndpoints.EndpointSpec spec,
                                                      Map<String, Object> params) {
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
            row.put("pre_close", prevClose != null ? prevClose : toDouble(f[2]));

            if (isIndex) {
                String idx = String.valueOf(params.get("indexCode"));
                row.put("index_code", idx);
                row.put("index_name", EastmoneyEndpoints.indexName(idx));
            } else if (isWeekly) {
                row.put("ts_code", String.valueOf(params.get("tsCode")));
                row.put("stock_name", txt(data, "name"));
            } else {
                row.put("ts_code", String.valueOf(params.get("tsCode")));
                row.put("stock_name", txt(data, "name"));
                // TODO M6: total_mv/circ_mv/pe/is_limit_up 等需其它接口补全
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

            prevClose = toDouble(f[2]);
            rows.add(row);
        }
        return rows;
    }

    /** ZT_POOL 解析器（涨跌停/炸板/强势/次新池，push2ex）。 */
    public static List<Map<String, Object>> parseZtPool(JsonNode data, EastmoneyEndpoints.EndpointSpec spec,
                                                       Map<String, Object> params) {
        List<Map<String, Object>> rows = new ArrayList<>();
        JsonNode pool = data.path("pool");
        if (!pool.isArray()) {
            return rows;
        }
        String tradeDate = EastmoneyEndpoints.requireTradeDate(params);
        String lt = String.valueOf(params.getOrDefault("limitType", spec.getPoolDefaultLimitType()));
        for (JsonNode n : pool) {
            Map<String, Object> row = new HashMap<>();
            row.put("ts_code", EastmoneyFieldMap.toTsCode(txt(n, "c"), txt(n, "m")));
            row.put("stock_name", txt(n, "n"));
            Double p = num(n, "p");
            row.put("latest_price", p != null ? p / 100.0 : null);
            row.put("pct_chg", num(n, "zdp"));
            Double ztpRaw = num(n, "ztp");
            row.put("ztp", ztpRaw != null ? ztpRaw / 100.0 : null);
            Integer lbc = toInt(num(n, "lbc"));
            row.put("board_pos", lbc);
            row.put("open_time", hhmmssToTime(txt(n, "fbt")));
            row.put("last_time", hhmmssToTime(txt(n, "lbt")));
            row.put("open_times", toInt(num(n, "zbc")));
            row.put("board_code", txt(n, "hybk"));
            row.put("board_name", null);                 // 实测响应不含 hymc
            row.put("fund", num(n, "fund"));
            row.put("amount", num(n, "amount"));
            row.put("ltsz", num(n, "ltsz"));
            row.put("tshare", num(n, "tshare"));
            row.put("hs", num(n, "hs"));
            row.put("zttj_ct", toInt(num(n, "zttj.ct")));
            row.put("zttj_days", toInt(num(n, "zttj.days")));
            row.put("zf", num(n, "zf"));
            row.put("zs", num(n, "zs"));
            row.put("lb", toInt(num(n, "lb")));
            row.put("nh", toInt(num(n, "nh")));
            row.put("ztf", txt(n, "ztf"));
            Double ztpRawVal = num(n, "ztp");
            if (ztpRawVal != null && ztpRawVal >= 1000000000.0) {
                row.put("ztp", null);
            } else {
                row.put("ztp", ztpRawVal != null ? ztpRawVal / 100.0 : null);
            }
            row.put("ipod", txt(n, "ipod"));
            row.put("o", toInt(num(n, "o")));
            row.put("od", toInt(num(n, "od")));
            row.put("ods", toInt(num(n, "ods")));
            row.put("type", lt);
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

    /** DATACENTER 解析器（龙虎榜 v1 端点）。 */
    public static List<Map<String, Object>> parseDatacenter(JsonNode data, EastmoneyEndpoints.EndpointSpec spec,
                                                           Map<String, Object> params) {
        List<Map<String, Object>> rows = new ArrayList<>();
        if (!data.isArray()) {
            return rows;
        }
        String tradeDate = EastmoneyEndpoints.requireTradeDate(params);
        if ("DRAGON_TIGER".equals(spec.getTaskType())) {
            for (JsonNode n : data) {
                Map<String, Object> row = new HashMap<>();
                String secucode = txt(n, "SECUCODE");
                row.put("ts_code", (secucode != null && !secucode.isEmpty()) ? secucode : tsCodeFromRaw(txt(n, "SECURITY_CODE")));
                row.put("stock_name", txt(n, "SECURITY_NAME_ABBR"));
                row.put("reason", txt(n, "EXPLAIN"));
                row.put("explanation", txt(n, "EXPLANATION"));
                row.put("total_buy", num(n, "BILLBOARD_BUY_AMT"));
                row.put("total_sell", num(n, "BILLBOARD_SELL_AMT"));
                row.put("net_buy", num(n, "BILLBOARD_NET_AMT"));
                row.put("billboard_deal_amt", num(n, "BILLBOARD_DEAL_AMT"));
                row.put("market", txt(n, "MARKET"));
                row.put("close_price", num(n, "CLOSE_PRICE"));
                row.put("change_rate", num(n, "CHANGE_RATE"));
                row.put("turnoverrate", num(n, "TURNOVERRATE"));
                row.put("free_market_cap", num(n, "FREE_MARKET_CAP"));
                row.put("buy_seat", toInt(num(n, "BUY_SEAT")));
                row.put("sell_seat", toInt(num(n, "SELL_SEAT")));
                row.put("buy_seat_new", toInt(num(n, "BUY_SEAT_NEW")));
                row.put("sell_seat_new", toInt(num(n, "SELL_SEAT_NEW")));
                row.put("buy_ratio", num(n, "BUY_RATIO"));
                row.put("sell_ratio", num(n, "SELL_RATIO"));
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
        } else { // DRAGON_TIGER_DETAIL
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

    /**
     * 北向资金解析器（东财 push2 kamt 实时端点，纯 JSON 非 JSONP）。
     * <p>响应结构：{@code data.{hk2sh,hk2sz,sh2hk,sz2hk}.{netBuyAmt,date2}}。
     * 北向净买入 = 沪股通(hk2sh) + 深股通(hk2sz)；trade_date 取响应 {@code date2}
     * （如 "2026-08-07"，缺失时回退 params.tradeDate）。返回单行 Map（落 northbound_flow 表，主键 trade_date）。</p>
     */
    public static List<Map<String, Object>> parseNorthbound(JsonNode root, EastmoneyEndpoints.EndpointSpec spec,
                                                            Map<String, Object> params) {
        List<Map<String, Object>> rows = new ArrayList<>();
        JsonNode data = root.path("data");
        if (data.isMissingNode() || data.isNull()) {
            return rows;
        }
        JsonNode hk2sh = data.path("hk2sh");   // 沪股通（北向买沪）
        JsonNode hk2sz = data.path("hk2sz");   // 深股通（北向买深）
        Double shNet = num(hk2sh, "netBuyAmt");   // 沪股通当日净买入(元)
        Double szNet = num(hk2sz, "netBuyAmt");   // 深股通当日净买入(元)
        double hkHoldNet = (shNet != null ? shNet : 0d) + (szNet != null ? szNet : 0d);
        String date2 = txt(hk2sh, "date2");        // 交易日，形如 "2026-08-07"
        String tradeDate = (date2 != null) ? date2 : String.valueOf(params.getOrDefault("tradeDate", ""));
        Map<String, Object> row = new HashMap<>();
        row.put("trade_date", tradeDate);
        row.put("hk_hold_net", hkHoldNet);  // 北向合计净买入(元)
        row.put("sh_net", shNet);           // 沪股通净买入(元)
        row.put("sz_net", szNet);           // 深股通净买入(元)
        rows.add(row);
        return rows;
    }

    // ----------------------------------------------------------------------
    // 工具
    // ----------------------------------------------------------------------

    /** taskType → board_type 映射（1地域 2行业 3概念）。响应无区分字段，靠 taskType 本身。 */
    static int taskTypeToBoardType(String taskType) {
        if (taskType == null) {
            return 0;
        }
        return switch (taskType) {
            case "REGION_DAILY", "REGION_BOARD" -> 1;
            case "INDUSTRY_DAILY", "INDUSTRY_BOARD" -> 2;
            case "CONCEPT_DAILY", "CONCEPT_BOARD" -> 3;
            default -> 0;
        };
    }

    /** 东财 HHMMSS（如 92500 = 09:25:00）转 HH:mm:ss；非数字（"-"）返回 null。 */
    public static String hhmmssToTime(String raw) {
        if (raw == null || raw.isEmpty()) return null;
        if (!raw.matches("\\d+")) return null;
        String s = String.format("%06d", Integer.parseInt(raw));
        if (s.length() != 6) return null;
        return s.substring(0, 2) + ":" + s.substring(2, 4) + ":" + s.substring(4, 6);
    }

    public static String txt(JsonNode n, String field) {
        JsonNode v = (n == null) ? MissingNode.getInstance() : n.get(field);
        if (v == null || v.isNull()) {
            return null;
        }
        String s = v.asText();
        return s.isEmpty() ? null : s;
    }

    public static Double num(JsonNode n, String field) {
        JsonNode v = (n == null) ? MissingNode.getInstance() : n.get(field);
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

    public static Object numOrText(JsonNode v) {
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

    public static Integer toInt(Double d) {
        return d == null ? null : d.intValue();
    }

    public static Integer parseInt(Object o) {
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

    public static Double toDouble(String s) {
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
    public static String tsCodeFromRaw(String code) {
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
        return code;
    }

    /** 东财 f12 裸码 + f13 市场码 → 带后缀指数代码(后缀跟东财 secid market 走)。 */
    private static String indexCodeFor(String f12Raw, String f13) {
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

    /** 分→元:除以 100,原值 null 返回 null。 */
    private static Double div100(Double d) {
        return d == null ? null : d / 100.0;
    }

    /** f152 数据状态枚举 → 中文描述(未知值返回 null)。 */
    private static String dataStatusText(String f152) {
        if (f152 == null || f152.isEmpty()) {
            return null;
        }
        return switch (f152) {
            case "1" -> "盘前";
            case "2" -> "盘中";
            case "3" -> "盘后";
            default -> null;
        };
    }
}