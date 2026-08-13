package com.dunwugudao.crawler.admin.pipeline;

import java.util.List;
import java.util.Map;

/** 单阶段执行结果。 */
public class PipelineStageResult {
    public String stage;
    public String status;           // DONE / FAILED / SKIP / IGNORED
    public int seededCount;
    public int expectedTotal;
    public int actualTotal;
    public int dupRows;
    public int lostRows;
    public long durationMs;
    public List<Long> taskIds;
    public List<ValidateResult> checkResults;
    public String errorMsg;

    public Map<String, Object> toMap() {
        return Map.of(
                "stage", stage,
                "status", status,
                "seededCount", seededCount,
                "expectedTotal", expectedTotal,
                "actualTotal", actualTotal,
                "dupRows", dupRows,
                "lostRows", lostRows,
                "durationMs", durationMs,
                "checkResults", checkResults == null ? List.of() : checkResults,
                "errorMsg", errorMsg == null ? "" : errorMsg
        );
    }
}
