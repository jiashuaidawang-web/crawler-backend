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
            new TaskSpec("BOARD_DAILY", 1, true, false, null, "板块每日行情（市场级）"),
            new TaskSpec("MAIN_FUND_STOCK", 1, true, false, null, "个股主力资金流（市场级）"),
            new TaskSpec("MAIN_FUND_BOARD", 1, true, false, null, "板块主力资金流（市场级）"),
            new TaskSpec("DRAGON_TIGER", 1, true, false, null, "龙虎榜（市场级，DragonTigerDetails 需父任务后补）"),
            new TaskSpec("STRONG_POOL", 1, true, false, null, "强势股池（市场级）"),
            new TaskSpec("STOCK_DAILY", 1, false, true, 1, "个股日线（逐券，需股票池）"),
            new TaskSpec("STOCK_WEEKLY", 1, false, true, 1, "个股周线（逐券，需股票池）"),
            new TaskSpec("INDEX_DAILY", 1, false, true, 1, "指数日线（逐券，需指数池）")
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

    private static final ObjectMapper OM = new ObjectMapper();

    /** 市场级 params：{"tradeDate":"DATE"} */
    public static String buildParams(String taskType, String date, String code) {
        return buildParams(taskType, date, code, null);
    }

    /**
     * params_json 构造（手写 JSON，禁止引入其它 JSON 库）。
     * <ul>
     *   <li>市场级（code==null 且 limitType==null）：{"tradeDate":"DATE"}</li>
     *   <li>涨停/跌停/炸板子任务（limitType!=null）：{"tradeDate":"DATE","limitType":"..."}</li>
     *   <li>STOCK_DAILY / STOCK_WEEKLY：{"tsCode":"CODE","tradeDate":"DATE"}</li>
     *   <li>INDEX_DAILY：{"indexCode":"CODE","tradeDate":"DATE"}</li>
     * </ul>
     */
    public static String buildParams(String taskType, String date, String code, String limitType) {
        ObjectNode n = OM.createObjectNode();
        if (limitType != null) {
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
