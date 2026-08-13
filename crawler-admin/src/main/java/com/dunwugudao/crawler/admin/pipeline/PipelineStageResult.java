package com.dunwugudao.crawler.admin.pipeline;

import java.util.List;
import java.util.Map;
import java.util.LinkedHashMap;

/** 单阶段执行结果。 */
public class PipelineStageResult {
    public String stage;
    public String status;           // DONE / FAILED / SKIP / IGNORED
    public int seededCount;
    public int expectedTotal;
    public int actualTotal;
    public int dupRows;
    public int lostRows;
    public int boardRelStockCount;   // STOCK_BY_BOARD:今日股票数(供明天比较)
    public int boardRelBoardCount;   // STOCK_BY_BOARD:今日板块数(供明天比较)
    public long durationMs;
    public List<Long> taskIds;
    public List<ValidateResult> checkResults;
    public String errorMsg;

    public Map<String, Object> toMap() {
        LinkedHashMap<String, Object> m = new LinkedHashMap<>();
        m.put("stage", stage);
        m.put("status", status);
        m.put("seededCount", seededCount);
        m.put("expectedTotal", expectedTotal);
        m.put("actualTotal", actualTotal);
        m.put("dupRows", dupRows);
        m.put("lostRows", lostRows);
        m.put("boardRelStockCount", boardRelStockCount);
        m.put("boardRelBoardCount", boardRelBoardCount);
        m.put("durationMs", durationMs);
        m.put("checkResults", checkResults == null ? List.of() : checkResults);
        m.put("errorMsg", errorMsg == null ? "" : errorMsg);
        return m;
    }
}
