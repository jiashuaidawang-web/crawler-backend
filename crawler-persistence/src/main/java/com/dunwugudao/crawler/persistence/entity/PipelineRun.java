package com.dunwugudao.crawler.persistence.entity;

import lombok.Data;
import java.time.LocalDate;
import java.time.LocalDateTime;

/** pipeline_run 实体:一次日批编排执行。 */
@Data
public class PipelineRun {
    private Long runId;
    private LocalDate runDate;
    private String status;          // RUNNING / SUCCESS / FAILED / ABORTED
    private LocalDateTime startedAt;
    private LocalDateTime finishedAt;
    private String summary;         // JSON
}
