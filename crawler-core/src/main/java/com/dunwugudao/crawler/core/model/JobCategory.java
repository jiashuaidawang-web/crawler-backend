package com.dunwugudao.crawler.core.model;

/**
 * Job 类别：BATCH=一次性执行, CONTINUOUS=持续运行。
 * <p>与 job_definition.job_category / crawl_task.job_type 对应。</p>
 */
public enum JobCategory {
    BATCH,
    CONTINUOUS
}
