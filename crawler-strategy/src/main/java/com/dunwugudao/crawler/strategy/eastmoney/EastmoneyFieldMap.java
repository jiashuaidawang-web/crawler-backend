package com.dunwugudao.crawler.strategy.eastmoney;

import java.util.HashMap;
import java.util.Map;

/**
 * 东方财富 clist / kline 接口 f 码 → schema 列名 静态映射表。
 * <p>基于 Playwright + urllib 实测真实响应（2026-08-01），非文档猜测。</p>
 * <p>实测覆盖：clist.stock（32 f 码）、clist.board（41 f 码）、clist.fund（8 f 码）、
 * kline（固定顺序11字段）、push2ex（17-19字段）、datacenter（30字段）。</p>
 *
 * <p>注意：东财 f12 不含市场后缀，需用 {@link #toTsCode(String, String)} 按 f13/f152 市场码补后缀。</p>
 */
public final class EastmoneyFieldMap {

    private EastmoneyFieldMap() {
    }

    /** f 码 → schema 列名（clist 通用投影）。 */
    private static final Map<String, String> F_TO_COL = new HashMap<>();

    static {
        // ---- 基础行情 ----
        F_TO_COL.put("f1", "__internal");       // 内部市场码（不直接落库）
        F_TO_COL.put("f2", "close");            // 最新价/收盘价
        F_TO_COL.put("f3", "pct_chg");          // 涨跌幅%
        F_TO_COL.put("f4", "chg_amount");       // 涨跌额
        F_TO_COL.put("f5", "vol");              // 成交量(手)
        F_TO_COL.put("f6", "amount");           // 成交额(元)
        F_TO_COL.put("f7", "amplitude");        // 振幅%
        F_TO_COL.put("f8", "turnover_alt");     // 换手率%（备用）
        F_TO_COL.put("f9", "pe");               // 市盈率(TTM)
        F_TO_COL.put("f10", "volume_ratio");    // 量比
        F_TO_COL.put("f11", "avg_price");       // 均价

        // ---- 代码/名称 ----
        F_TO_COL.put("f12", "ts_code");          // 代码（无后缀，需 toTsCode 补）
        F_TO_COL.put("f13", "__market");        // 市场码（0深/1沪，内部用）
        F_TO_COL.put("f14", "name");            // 名称（按 taskType 决定列名）

        // ---- 价格 ----
        F_TO_COL.put("f15", "high");            // 最高价
        F_TO_COL.put("f16", "low");             // 最低价
        F_TO_COL.put("f17", "open");            // 开盘价
        F_TO_COL.put("f18", "pre_close");       // 昨日收盘价
        F_TO_COL.put("f19", "unknown_f19");     // 委差/委比（盘口接口）

        // ---- 市值 ----
        F_TO_COL.put("f20", "total_mv");        // 总市值(元)
        F_TO_COL.put("f21", "circ_mv");         // 流通市值(元)
        F_TO_COL.put("f22", "turn_speed");      // 涨速/委比
        F_TO_COL.put("f23", "pb");              // 市净率(PB)
        F_TO_COL.put("f24", "unknown_f24");     // 待确认
        F_TO_COL.put("f25", "unknown_f25");     // 待确认
        F_TO_COL.put("f26", "unknown_f26");     // 板块关联（待确认）

        // ---- 资金流 ----
        F_TO_COL.put("f62", "main_net");        // 主力净流入(=超大单+大单)
        F_TO_COL.put("f66", "super_big");       // 超大单净流入
        F_TO_COL.put("f72", "big_net");         // 大单净流入
        F_TO_COL.put("f78", "mid_net");         // 中单净流入
        F_TO_COL.put("f84", "small_net");       // 小单净流入

        // ---- 财务/板块 ----
        F_TO_COL.put("f104", "up_count");       // 板块上涨家数
        F_TO_COL.put("f105", "down_count");     // 板块下跌家数
        F_TO_COL.put("f107", "unknown_f107");   // 待确认
        F_TO_COL.put("f115", "pe_static");      // 静态市盈率
        F_TO_COL.put("f116", "total_shares");   // 总股本
        F_TO_COL.put("f117", "circ_shares");    // 流通股本

        // ---- 领涨/行业 ----
        F_TO_COL.put("f128", "leader_code");    // 领涨股代码
        F_TO_COL.put("f136", "unknown_f136");   // 待确认
        F_TO_COL.put("f140", "board_code");     // 所属行业代码
        F_TO_COL.put("f141", "board_code2");    // 所属概念代码

        // ---- 其他 ----
        F_TO_COL.put("f152", "market_code");    // 市场码（0深/1沪/2京）
        F_TO_COL.put("f173", "unknown_f173");   // 待确认
        F_TO_COL.put("f184", "turnover");       // 换手率%
    }

    /** 是否存在该 f 码的映射。 */
    public static boolean contains(String fcode) {
        return F_TO_COL.containsKey(fcode);
    }

    /**
     * 返回 f 码对应的 schema 列名（不存在返回 null）。
     * 注：f12/f14 的语义（ts_code vs board_code、stock_name vs board_name）取决于 taskType，
     * 此处返回通用名，具体落库列由解析器按 taskType 决定。
     */
    public static String schemaCol(String fcode) {
        return F_TO_COL.get(fcode);
    }

    /**
     * 由东财 f12（无后缀代码）与 f13（市场码）拼接成带后缀的代码。
     * <ul>
     *   <li>f13 = 1 → .SH（沪市）</li>
     *   <li>f13 = 0 → .SZ（深市）</li>
     *   <li>其它 → 原样返回（如北交所/未知，调用方需自行兜底）</li>
     * </ul>
     */
    public static String toTsCode(String f12, String f13) {
        if (f12 == null) {
            return null;
        }
        String suffix;
        if ("1".equals(f13)) {
            suffix = ".SH";
        } else if ("0".equals(f13)) {
            suffix = ".SZ";
        } else {
            suffix = "";
        }
        return f12 + suffix;
    }

    /**
     * datacenter 大写列名 → schema 列名映射（龙虎榜）。
     * 实测 datacenter 返回大写列名（如 SECURITY_CODE、BILLBOARD_NET_AMT），需转成驼峰。
     */
    public static final Map<String, String> DATACENTER_COL_MAP = buildDatacenterMap();

    private static Map<String, String> buildDatacenterMap() {
        Map<String, String> m = new HashMap<>();
        m.put("SECUCODE", "ts_code");           // 带后缀代码（000009.SZ）
        m.put("SECURITY_CODE", "ts_code_raw");  // 无后缀代码
        m.put("SECURITY_NAME_ABBR", "stock_name");
        m.put("EXPLAIN", "reason");
        m.put("EXPLANATION", "explanation");
        m.put("BILLBOARD_BUY_AMT", "total_buy");
        m.put("BILLBOARD_SELL_AMT", "total_sell");
        m.put("BILLBOARD_NET_AMT", "net_buy");
        m.put("BILLBOARD_DEAL_AMT", "billboard_deal_amt");
        m.put("MARKET", "market");
        m.put("CLOSE_PRICE", "close_price");
        m.put("CHANGE_RATE", "change_rate");
        m.put("TURNOVERRATE", "turnoverrate");
        m.put("FREE_MARKET_CAP", "free_market_cap");
        m.put("BUY_SEAT", "buy_seat");
        m.put("SELL_SEAT", "sell_seat");
        m.put("BUY_SEAT_NEW", "buy_seat_new");
        m.put("SELL_SEAT_NEW", "sell_seat_new");
        m.put("BUY_RATIO", "buy_ratio");
        m.put("SELL_RATIO", "sell_ratio");
        m.put("ACCUM_AMOUNT", "accum_amount");
        m.put("DEAL_AMOUNT_RATIO", "deal_amount_ratio");
        m.put("DEAL_NET_RATIO", "deal_net_ratio");
        m.put("CHANGE_TYPE", "change_type");
        m.put("SECURITY_INNER_CODE", "security_inner_code");
        m.put("SECURITY_TYPE_CODE", "security_type_code");
        m.put("TRADE_ID", "trade_id");
        m.put("TRADE_MARKET", "trade_market");
        m.put("TRADE_MARKET_CODE", "trade_market_code");
        return m;
    }
}
