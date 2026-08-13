package com.dunwugudao.crawler.admin.pipeline;

/** 阶段执行失败后的策略。 */
public enum FailurePolicy {
    HALT,       // 阻断下游(关键阶段,如 STOCK_DAILY)
    SKIP,       // 跳过该阶段,继续下游(非关键)
    CONTINUE    // 忽略失败继续
}
