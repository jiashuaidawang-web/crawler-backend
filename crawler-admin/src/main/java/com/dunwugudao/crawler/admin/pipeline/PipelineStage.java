package com.dunwugudao.crawler.admin.pipeline;

import java.util.List;
import java.util.Set;

/**
 * 日批编排阶段定义(收盘后全量)。
 * seq 为执行顺序;dependsOn 为依赖的前置阶段;policy 为失败策略;
 * taskTypes 为该阶段对应的 crawl_task.task_type 列表(用于完成探测)。
 */
public enum PipelineStage {

    STOCK_DAILY        (1,  FailurePolicy.HALT,    Set.of(),                List.of("STOCK_DAILY"),            "股票日线"),
    REGION_DAILY       (2,  FailurePolicy.SKIP,    Set.of(),                List.of("REGION_DAILY"),           "地域日线"),
    INDUSTRY_DAILY     (3,  FailurePolicy.SKIP,    Set.of(),                List.of("INDUSTRY_DAILY"),         "行业日线"),
    CONCEPT_DAILY      (4,  FailurePolicy.SKIP,    Set.of(),                List.of("CONCEPT_DAILY"),          "概念日线"),
    LIMIT_UP           (5,  FailurePolicy.SKIP,    Set.of(),                List.of("LIMIT_UP"),               "涨停池"),
    LIMIT_DOWN         (6,  FailurePolicy.SKIP,    Set.of(),                List.of("LIMIT_DOWN"),             "跌停池"),
    LIMIT_ZHABAN       (7,  FailurePolicy.SKIP,    Set.of(),                List.of("LIMIT_ZHABAN"),           "炸板池"),
    STRONG_POOL        (8,  FailurePolicy.SKIP,    Set.of(),                List.of("STRONG_POOL"),            "强势股池"),
    CIXIN_POOL         (9,  FailurePolicy.SKIP,    Set.of(),                List.of("CIXIN_POOL"),             "次新股池"),
    INDEX_DAILY        (10, FailurePolicy.SKIP,    Set.of(),                List.of("INDEX_DAILY"),            "指数日线"),
    DRAGON_TIGER       (11, FailurePolicy.SKIP,    Set.of(),                List.of("DRAGON_TIGER"),           "龙虎榜"),
    DRAGON_TIGER_DETAIL(12, FailurePolicy.SKIP,    Set.of(DRAGON_TIGER),    List.of("DRAGON_TIGER_DETAIL"),    "龙虎榜明细"),
    BOARD_BASIC        (13, FailurePolicy.SKIP,    Set.of(),                List.of("REGION_BOARD","INDUSTRY_BOARD","CONCEPT_BOARD"), "板块基础"),
    STOCK_WEEKLY       (14, FailurePolicy.SKIP,    Set.of(),                List.of(),                          "股票周线"),
    STOCK_BY_BOARD     (15, FailurePolicy.SKIP,    Set.of(),                List.of("STOCK_BY_BOARD"),         "板块个股"),
    MAIN_FUND_STOCK    (16, FailurePolicy.SKIP,    Set.of(),                List.of("MAIN_FUND_STOCK"),        "个股主力资金流"),
    MAIN_FUND_BOARD    (17, FailurePolicy.SKIP,    Set.of(),                List.of("MAIN_FUND_BOARD"),        "板块主力资金流"),
    NORTHBOUND         (18, FailurePolicy.SKIP,    Set.of(),                List.of("NORTHBOUND_FLOW"),        "北向资金");

    private final int seq;
    private final FailurePolicy policy;
    private final Set<PipelineStage> dependsOn;
    private final List<String> taskTypes;
    private final String displayName;

    PipelineStage(int seq, FailurePolicy policy, Set<PipelineStage> dependsOn, List<String> taskTypes, String displayName) {
        this.seq = seq;
        this.policy = policy;
        this.dependsOn = dependsOn;
        this.taskTypes = taskTypes;
        this.displayName = displayName;
    }

    public int getSeq() { return seq; }
    public FailurePolicy getPolicy() { return policy; }
    public Set<PipelineStage> getDependsOn() { return dependsOn; }
    public List<String> getTaskTypes() { return taskTypes; }
    public String getDisplayName() { return displayName; }
}
