package com.dunwugudao.crawler.persistence.entity;

import lombok.Data;
import java.time.LocalDateTime;

/** pipeline_stage 实体:编排的一个阶段。 */
@Data
public class PipelineStageRecord {
    private Long stageId;
    private Long runId;
    private String stageName;
    private Integer seq;
    private String status;          // PENDING / RUNNING / SKIP / FAILED / DONE / IGNORED
    private Integer seededCount;
    private Integer expectedTotal;  // 上游总数(校验真值)
    private Integer actualTotal;    // CK 实际行数
    private Integer dupRows;        // 重复行数
    private Integer lostRows;       // 真正丢失行数
    private Long durationMs;
    private String checkResult;     // JSON
    private String errorMsg;
    private LocalDateTime startedAt;
    private LocalDateTime finishedAt;
}
