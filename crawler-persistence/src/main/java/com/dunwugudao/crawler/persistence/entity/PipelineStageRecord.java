package com.dunwugudao.crawler.persistence.entity;

import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import lombok.Builder;
import java.time.LocalDateTime;

/** pipeline_stage 实体:编排的一个阶段。 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PipelineStageRecord {
    private Long stageId;
    private Long runId;
    private String stageName;
    private String displayName;    // 阶段中文名(运行时从 PipelineStage 带入)
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
    private String userMessage;     // 人话摘要(前端展示,运行时生成,不持久化)
    private LocalDateTime startedAt;
    private LocalDateTime finishedAt;
}
