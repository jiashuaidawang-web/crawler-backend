package com.dunwugudao.crawler.persistence.entity;import com.dunwugudao.crawler.core.model.SourceType;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * crawl_task 表实体（PART D）。字段严格对应 schema：
 * task_id / task_type / source(SMALLINT) / url / params_json / status / priority /
 * retry_count / max_retry / next_retry_at / last_node / started_at / finished_at /
 * duration_ms / unique_key / checkpoint / expected_count / actual_count / error_msg /
 * created_at / updated_at / executor_type / job_type / worker_id / progress_pct / pid。
 */
@Data
public class CrawlTask {

    private Long taskId;

    private String taskType;

    private SourceType source;

    private String url;
    private String paramsJson;
    private String status;
    private Integer priority;
    private Integer retryCount;
    private Integer maxRetry;
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

    /** 执行器类型: JAVA=Java Worker, PYTHON=Python Worker */
    private String executorType = "JAVA";

    /** 任务类型: BATCH=一次性, CONTINUOUS=持续运行 */
    private String jobType = "BATCH";

    /** 执行节点 ID */
    private String workerId;

    /** 进度百分比 (0-100, CONTINUOUS 用) */
    private Integer progressPct;

    /** 操作系统进程 PID */
    private Integer pid;
}
