package com.dunwugudao.crawler.core.model;

/**
 * 任务状态机（与 crawl_task.status VARCHAR 严格对应）。
 * <p>PENDING → CLAIMED → SUCCESS / (FAILED|RETRY → RETRY → ... → DEAD)</p>
 */
public enum TaskStatus {
    PENDING,
    CLAIMED,
    SUCCESS,
    FAILED,
    RETRY,
    DEAD
}
