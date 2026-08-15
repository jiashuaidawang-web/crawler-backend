package com.dunwugudao.crawler.admin.pipeline;

import java.util.List;
import java.util.Set;

/**
 * 日批编排阶段定义(收盘后全量)。
 * seq 为执行顺序;dependsOn 为依赖的前置阶段;policy 为失败策略;
 * taskTypes 为该阶段对应的 crawl_task.task_type 列表(用于完成探测)。
 */
public enum PipelineStage {

    STOCK_DAILY        (1,  FailurePolicy.HALT,    Set.of(),                List.of("STOCK_DAILY")),
    REGION_DAILY       (2,  FailurePolicy.SKIP,    Set.of(),                List.of("REGION_DAILY")),
    INDUSTRY_DAILY     (3,  FailurePolicy.SKIP,    Set.of(),                List.of("INDUSTRY_DAILY")),
    CONCEPT_DAILY      (4,  FailurePolicy.SKIP,    Set.of(),                List.of("CONCEPT_DAILY")),
    LIMIT_POOL         (5,  FailurePolicy.SKIP,    Set.of(),                List.of("LIMIT_UP","LIMIT_DOWN","LIMIT_ZHABAN")),
    STRONG_POOL        (6,  FailurePolicy.SKIP,    Set.of(),                List.of("STRONG_POOL")),
    CIXIN_POOL         (7,  FailurePolicy.SKIP,    Set.of(),                List.of("CIXIN_POOL")),
    INDEX_DAILY        (8,  FailurePolicy.SKIP,    Set.of(),                List.of("INDEX_DAILY")),
    DRAGON_TIGER       (9,  FailurePolicy.SKIP,    Set.of(),                List.of("DRAGON_TIGER")),
    BOARD_BASIC        (10, FailurePolicy.SKIP,    Set.of(),                List.of("REGION_BOARD","INDUSTRY_BOARD","CONCEPT_BOARD")),
    STOCK_BY_BOARD     (11, FailurePolicy.SKIP,    Set.of(),                List.of("STOCK_BY_BOARD")),
    STOCK_WEEKLY       (12, FailurePolicy.SKIP,    Set.of(),                List.of()),
    DRAGON_TIGER_DETAIL(13, FailurePolicy.SKIP,    Set.of(DRAGON_TIGER),    List.of("DRAGON_TIGER_DETAIL")),
    MAIN_FUND_STOCK    (14, FailurePolicy.SKIP,    Set.of(),                List.of("MAIN_FUND_STOCK")),
    MAIN_FUND_BOARD    (15, FailurePolicy.SKIP,    Set.of(),                List.of("MAIN_FUND_BOARD")),
    NORTHBOUND         (16, FailurePolicy.SKIP,    Set.of(),                List.of("NORTHBOUND_FLOW"));

    private final int seq;
    private final FailurePolicy policy;
    private final Set<PipelineStage> dependsOn;
    private final List<String> taskTypes;

    PipelineStage(int seq, FailurePolicy policy, Set<PipelineStage> dependsOn, List<String> taskTypes) {
        this.seq = seq;
        this.policy = policy;
        this.dependsOn = dependsOn;
        this.taskTypes = taskTypes;
    }

    public int getSeq() { return seq; }
    public FailurePolicy getPolicy() { return policy; }
    public Set<PipelineStage> getDependsOn() { return dependsOn; }
    public List<String> getTaskTypes() { return taskTypes; }
}
