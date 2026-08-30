package com.dunwugudao.crawler.persistence.entity;

import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * job_execution 表实体。
 * <p>每天每 job 一条执行记录，前端概览核心数据来源。</p>
 */
@Data
public class JobExecution {

    private Long executionId;

    /** 关联 job_definition.job_type */
    private String jobType;

    /** 交易日期 */
    private LocalDate tradeDate;

    /** 执行节点 */
    private String workerId;

    /** PENDING / RUNNING / SUCCESS / FAILED / STOPPED */
    private String status;

    private LocalDateTime startedAt;
    private LocalDateTime finishedAt;
    private Long durationMs;

    /** 影响行数 (写入 CK 的行数) */
    private Long rowsAffected;

    private String errorMsg;

    /** 执行时配置快照 */
    private String configSnapshot;

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
