package com.dunwugudao.crawler.persistence.entity;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * job_definition 表实体。
 * <p>Job 定义、默认配置、调度规则。</p>
 */
@Data
public class JobDefinition {

    /** 唯一标识: L1_CAPTURE, L2_CAPTURE, ... */
    private String jobType;

    /** 显示名 */
    private String displayName;

    /** JAVA / PYTHON */
    private String executorType;

    /** BATCH / CONTINUOUS */
    private String jobCategory;

    private String description;

    /** JSON: 默认参数 */
    private String defaultConfig;

    /** 调度策略: MARKET_HOURS(盘中连续), AFTER_CLOSE(收盘后), ONCE_DAILY(每日一次), MANUAL(手动) */
    private String scheduleStrategy;

    /** Cron 表达式 (AFTER_CLOSE / ONCE_DAILY 用) */
    private String scheduleCron;

    /** 是否依赖开盘时间 */
    private Boolean marketDependent;

    /** 是否随 worker 启动自动开始 */
    private Boolean autoStart;

    private Boolean enabled;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
