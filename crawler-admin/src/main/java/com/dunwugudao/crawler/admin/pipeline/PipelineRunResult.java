package com.dunwugudao.crawler.admin.pipeline;

import com.dunwugudao.crawler.persistence.entity.PipelineStageRecord;
import java.util.List;

/** 跑批执行结果(对外返回)。 */
public record PipelineRunResult(String date, String status,
                                List<PipelineStageRecord> stages, String summary) {
}
