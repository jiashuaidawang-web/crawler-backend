package com.dunwugudao.crawler.persistence.entity;import com.dunwugudao.crawler.core.model.SourceType;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * crawl_task 表实体（PART D）。字段严格对应 schema：
 * task_id / task_type / source(SMALLINT) / url / params_json / status / priority /
 * retry_count / max_retry / next_retry_at / last_node / started_at / finished_at /
 * duration_ms / unique_key / checkpoint / expected_count / actual_count / error_msg /
 * created_at / updated_at。
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
}
