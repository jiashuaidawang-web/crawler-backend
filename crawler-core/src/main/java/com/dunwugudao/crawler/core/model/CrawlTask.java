package com.dunwugudao.crawler.core.model;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 核心任务模型，字段对齐 schema-opengauss.sql PART D 的 {@code crawl_task} 表。
 * <p>注意：这是 core 层“纯领域”对象，与 persistence 层的 MyBatis-Plus 实体分离；
 * worker 在认领后负责将持久化实体转换为本对象再交给策略执行。</p>
 */
@Data
public class CrawlTask {
    private Long taskId;
    private String taskType;
    private SourceType source;
    private String url;
    private String paramsJson;
    private TaskStatus status;
    private int priority;
    private int retryCount;
    private int maxRetry;
    private LocalDateTime nextRetryAt;
    private String lastNode;
    private LocalDateTime startedAt;
    private LocalDateTime finishedAt;
    private Long durationMs;
    private String uniqueKey;
    private String checkpoint;
    private Integer expectedCount;
    private Integer actualCount;
    private String errorMsg;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
