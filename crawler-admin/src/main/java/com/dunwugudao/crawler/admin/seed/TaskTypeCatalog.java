package com.dunwugudao.crawler.admin.seed;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.List;

/**
 * 可被种子的 taskType 集中目录（M3-2）。
 * <p>
 * 设计要点：
 * <ul>
 *   <li>{@code marketWide=true}：市场级任务，每日/每历史日一条。这类任务产出数量波动大，
 *       <b>不做 VOLUME_DEVIATION 量校验</b>（defaultExpected=null），避免误报。</li>
 *   <li>{@code perInstrument=true}：逐券/逐指数任务，每只（或每指数）一条。行级产出稳定，
 *       <b>做 VOLUME_DEVIATION 量校验</b>（defaultExpected=1，单只单日一行）。</li>
 *   <li>{@code LIMIT_POOL} 是市场级，但每日需拆成 limit_up / limit_down / zhaban 三个子任务，
 *       由 {@link com.dunwugudao.crawler.admin.seed.SeedGenerator} 专门展开。</li>
 * </ul>
 */
public final class TaskTypeCatalog {

    private TaskTypeCatalog() {
    }

    /** 市场级 / 逐券的 limit 子类型常量（LIMIT_POOL 的展开）。 */
    public static final String LIMIT_UP = "LIMIT_UP";
    public static final String LIMIT_DOWN = "LIMIT_DOWN";
    public static final String LIMIT_ZHABAN = "LIMIT_ZHABAN";

    /** 板块基础维表任务类型（独立抓取 board_basic，不依赖 board_daily 副作用）。 */
    public static final String REGION_BOARD = "REGION_BOARD";
    public static final String INDUSTRY_BOARD = "INDUSTRY_BOARD";
    public static final String CONCEPT_BOARD = "CONCEPT_BOARD";

    /** 集中任务规格。sourceCode 默认 1（东方财富），实际 seed 时由入参 source 覆盖。 */
    public record TaskSpec(
            String taskType,
            int sourceCode,
            boolean marketWide,
            boolean perInstrument,
            Integer defaultExpected,
            String desc
    ) {
    }

    public static final List<TaskSpec> ALL = List.of(
            new TaskSpec("LIMIT_POOL", 1, true, false, null,
                    "涨停/跌停/炸板池（每日拆成 limit_up/limit_down/zhaban 三个子任务）"),
            new TaskSpec("CIXIN_POOL", 1, true, false, null, "次新股池（市场级）"),
            new TaskSpec("REGION_DAILY", 1, true, false, null, "地域板块每日行情（市场级，board_type=1）"),
            new TaskSpec("INDUSTRY_DAILY", 1, true, false, null, "行业板块每日行情（市场级，board_type=2）"),
            new TaskSpec("CONCEPT_DAILY", 1, true, false, null, "概念板块每日行情（市场级，board_type=3）"),
            new TaskSpec(REGION_BOARD, 1, true, false, null, "地域板块基础维表（board_basic，board_type=1）"),
            new TaskSpec(INDUSTRY_BOARD, 1, true, false, null, "行业板块基础维表（board_basic，board_type=2）"),
            new TaskSpec(CONCEPT_BOARD, 1, true, false, null, "概念板块基础维表（board_basic，board_type=3）"),
            new TaskSpec("MAIN_FUND_STOCK", 1, true, false, null, "个股主力资金流（市场级）"),
            new TaskSpec("MAIN_FUND_BOARD", 1, true, false, null, "板块主力资金流（市场级）"),
            new TaskSpec("DRAGON_TIGER", 1, true, false, null, "龙虎榜（市场级）"),
            new TaskSpec("STRONG_POOL", 1, true, false, null, "强势股池（市场级）"),
            new TaskSpec("STOCK_DAILY", 1, true, false, null, "个股日线（市场级，按页拆任务，每页 100 条）"),
            new TaskSpec("STOCK_WEEKLY", 1, false, true, 1, "个股周线（逐券）"),
            new TaskSpec("INDEX_DAILY", 1, true, false, null, "指数日线（市场级, push2 clist 全市场快照 43 只）"),
            new TaskSpec("STOCK_DAILY_HISTORY", 1, false, true, 1, "个股日K历史回填（逐券，push2his kline 拿满历史）"),
            new TaskSpec("STOCK_KLINE_MINUTE", 1, false, true, 1, "个股分钟K线（逐券，push2his kline klt=1，量价数据）"),
            new TaskSpec("STOCK_BY_BOARD", 1, false, false, null, "板块-个股关系（逐板块，需 board_basic 表）"),
            new TaskSpec("THS_PLATE", 0, true, false, null, "同花顺板块基础维表（地域/行业/概念，浏览器策略）")
    );

    public static List<TaskSpec> marketWideTypes() {
        return ALL.stream().filter(TaskSpec::marketWide).toList();
    }

    public static List<TaskSpec> perInstrumentTypes() {
        return ALL.stream().filter(TaskSpec::perInstrument).toList();
    }

    /** 市场级唯一键：taskType|source|date */
    public static String buildUniqueKey(String taskType, int source, String date) {
        return taskType + "|" + source + "|" + date;
    }

    /** 逐券唯一键：taskType|source|code|date */
    public static String buildUniqueKey(String taskType, int source, String code, String date) {
        return taskType + "|" + source + "|" + code + "|" + date;
    }

    /** STOCK_DAILY 分页唯一键：taskType|source|date|pn */
    public static String buildPageUniqueKey(String taskType, int source, String date, int pn) {
        return taskType + "|" + source + "|" + date + "|" + pn;
    }

    /** STOCK_DAILY 专用：带页码（pn 从 1 开始）和交易日。 */
    public static String buildPageParams(String date, int pn) {
        ObjectNode n = OM.createObjectNode();
        n.put("tradeDate", date);
        n.put("pn", pn);
        try {
            return OM.writeValueAsString(n);
        } catch (Exception e) {
            return "{\"tradeDate\":\"" + date + "\",\"pn\":" + pn + "}";
        }
    }

    private static final ObjectMapper OM = new ObjectMapper();

    /** 市场级 params：{"tradeDate":"DATE"} */
    public static String buildParams(String taskType, String date, String code) {
        return buildParams(taskType, date, code, null);
    }

    /**
     * params_json 构造（手写 JSON，禁止引入其它 JSON 库）。
     * <ul>
     *   <li>市场级（code==null 且 limitType==null 且 boardCode==null 且 boardType==null）：{"tradeDate":"DATE"}</li>
     *   <li>涨停/跌停/炸板子任务（limitType!=null）：{"tradeDate":"DATE","limitType":"..."}</li>
     *   <li>REGION_DAILY / INDUSTRY_DAILY / CONCEPT_DAILY（boardType!=null）：{"boardType":N,"tradeDate":"DATE"}</li>
     *   <li>STOCK_DAILY / STOCK_WEEKLY：{"tsCode":"CODE","tradeDate":"DATE"}</li>
     *   <li>INDEX_DAILY：{"indexCode":"CODE","tradeDate":"DATE"}</li>
     *   <li>STOCK_BY_BOARD：{"boardCode":"CODE","boardName":"...","boardType":N,"tradeDate":"DATE"}</li>
     * </ul>
     */
    public static String buildParams(String taskType, String date, String code, String limitType) {
        return buildParams(taskType, date, code, limitType, null, null, null);
    }

    /** 板块行情专用：带 boardType（REGION/INDUSTRY/CONCEPT_DAILY）。 */
    public static String buildParams(String taskType, String date, Integer boardType) {
        ObjectNode n = OM.createObjectNode();
        n.put("boardType", boardType);
        n.put("tradeDate", date);
        try {
            return OM.writeValueAsString(n);
        } catch (Exception e) {
            return "{\"boardType\":" + boardType + ",\"tradeDate\":\"" + date + "\"}";
        }
    }

    /** STOCK_BY_BOARD 专用：带 boardCode / boardName / boardType。 */
    public static String buildParams(String taskType, String date, String code, String limitType,
                                     String boardCode, String boardName, Integer boardType) {
        ObjectNode n = OM.createObjectNode();
        if (boardCode != null) {
            n.put("boardCode", boardCode);
            if (boardName != null) {
                n.put("boardName", boardName);
            }
            if (boardType != null) {
                n.put("boardType", boardType);
            }
            n.put("tradeDate", date);
        } else if (limitType != null) {
            n.put("tradeDate", date);
            n.put("limitType", limitType);
        } else if (code != null) {
            if ("INDEX_DAILY".equals(taskType)) {
                n.put("indexCode", code);
            } else {
                n.put("tsCode", code);
            }
            n.put("tradeDate", date);
        } else {
            n.put("tradeDate", date);
        }
        try {
            return OM.writeValueAsString(n);
        } catch (Exception e) {
            // 纯字符串拼接，不可能抛异常；兜底直接拼
            if (boardCode != null) {
                return "{\"boardCode\":\"" + boardCode + "\",\"tradeDate\":\"" + date + "\"}";
            }
            if (limitType != null) {
                return "{\"tradeDate\":\"" + date + "\",\"limitType\":\"" + limitType + "\"}";
            }
            if (code != null) {
                String key = "INDEX_DAILY".equals(taskType) ? "indexCode" : "tsCode";
                return "{\"" + key + "\":\"" + code + "\",\"tradeDate\":\"" + date + "\"}";
            }
            return "{\"tradeDate\":\"" + date + "\"}";
        }
    }
}
