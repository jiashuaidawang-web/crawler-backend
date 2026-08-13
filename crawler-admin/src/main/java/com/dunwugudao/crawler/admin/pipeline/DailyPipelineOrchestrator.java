package com.dunwugudao.crawler.admin.pipeline;

import com.dunwugudao.crawler.persistence.entity.PipelineRun;
import com.dunwugudao.crawler.persistence.entity.PipelineStageRecord;
import com.dunwugudao.crawler.persistence.mapper.PipelineMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;

/**
 * 日批编排器:顺序执行各阶段(seed → 等待完成 → 校验 → 失败策略)。
 *
 * <p>生命周期模型:</p>
 * <ul>
 *   <li>run 初始化时预建所有 stage 行(status=PENDING,幂等:已存在不重复插)</li>
 *   <li>执行时按 (run_id,stage_name) update 该行(不新建行)</li>
 *   <li>FAILED 重跑时 reset 所有 stage 为 PENDING(不清零重建,避免行累积)</li>
 *   <li>幂等:RUNNING/SUCCESS 直接返回 status,不重复执行</li>
 * </ul>
 */
@Service
public class DailyPipelineOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(DailyPipelineOrchestrator.class);

    /** 活跃阶段(收盘后全量,按依赖排序)。DRAGON_TIGER_DETAIL 依赖串联,暂不纳入。 */
    private static final List<PipelineStage> ACTIVE_STAGES = List.of(
            PipelineStage.STOCK_DAILY,
            PipelineStage.REGION_DAILY,
            PipelineStage.INDUSTRY_DAILY,
            PipelineStage.CONCEPT_DAILY,
            PipelineStage.MAIN_FUND_STOCK,
            PipelineStage.MAIN_FUND_BOARD,
            PipelineStage.LIMIT_POOL,
            PipelineStage.STRONG_POOL,
            PipelineStage.CIXIN_POOL,
            PipelineStage.NORTHBOUND,
            PipelineStage.INDEX_DAILY,
            PipelineStage.DRAGON_TIGER
    );

    private final PipelineMapper pipelineMapper;
    private final StageCompletionDetector completionDetector;
    private final List<PipelineValidator> validators;
    private final StageSeeder stageSeeder;

    @Value("${crawler.pipeline.source:1}")
    private int defaultSource;

    @Value("${crawler.pipeline.await-timeout-min:60}")
    private int awaitTimeoutMin;

    @Value("${crawler.pipeline.poll-interval-sec:30}")
    private int pollIntervalSec;

    public DailyPipelineOrchestrator(PipelineMapper pipelineMapper,
                                    StageCompletionDetector completionDetector,
                                    List<PipelineValidator> validators,
                                    StageSeeder stageSeeder) {
        this.pipelineMapper = pipelineMapper;
        this.completionDetector = completionDetector;
        this.validators = validators;
        this.stageSeeder = stageSeeder;
    }

    /** 幂等入口:跑全日批。FAILED 会 reset 重跑,SUCCESS/RUNNING 直接返回。 */
    public PipelineRunResult run(String dateStr) {
        LocalDate date = LocalDate.parse(dateStr);
        Long runId = ensureRun(date, true);
        if (runId == null) {
            return status(dateStr);
        }
        return execute(date, runId, false);
    }

    /** 断点续跑:从首个非终态阶段继续(不 reset,用于 RUNNING 中断后继续)。 */
    public PipelineRunResult resume(String dateStr) {
        LocalDate date = LocalDate.parse(dateStr);
        PipelineRun run = pipelineMapper.selectRunByDate(date);
        if (run == null) {
            return run(dateStr);
        }
        return execute(date, run.getRunId(), false);
    }

    public PipelineRunResult status(String dateStr) {
        LocalDate date = LocalDate.parse(dateStr);
        PipelineRun run = pipelineMapper.selectRunByDate(date);
        if (run == null) {
            return new PipelineRunResult(dateStr, "NONE", List.of(), "尚未发起跑批");
        }
        List<PipelineStageRecord> stages = pipelineMapper.selectStages(run.getRunId());
        return new PipelineRunResult(dateStr, run.getStatus(), stages, run.getSummary());
    }

    // ---------------- run 初始化 ----------------

    /**
     * 确保某日有 RUNNING 的 run(并预建 stage 行)。
     *
     * @param resetIfExists FAILED/ABORTED 时 reset 重跑
     * @return runId(已有 RUNNING/SUCCESS 时返回 null,表示无需执行)
     */
    private Long ensureRun(LocalDate date, boolean resetIfExists) {
        PipelineRun existing = pipelineMapper.selectRunByDate(date);
        if (existing != null) {
            if ("RUNNING".equals(existing.getStatus()) || "SUCCESS".equals(existing.getStatus())) {
                return null; // 幂等:已在跑或已完成
            }
            // FAILED/ABORTED → reset 重跑
            pipelineMapper.resetStagesToPending(existing.getRunId());
            pipelineMapper.resetRunToRunning(existing.getRunId());
            initStages(existing.getRunId());
            return existing.getRunId();
        }
        // 新建 run
        PipelineRun r = new PipelineRun();
        r.setRunDate(date);
        pipelineMapper.insertRunIgnoreConflict(r);
        Long runId = pipelineMapper.selectRunIdByDate(date);
        if (runId == null) {
            return null;
        }
        initStages(runId);
        return runId;
    }

    /** 预建所有阶段行(幂等)。 */
    private void initStages(Long runId) {
        for (PipelineStage stage : ACTIVE_STAGES) {
            pipelineMapper.insertStageIfNotExists(runId, stage.name(), stage.getSeq());
        }
    }

    // ---------------- 核心执行 ----------------

    private PipelineRunResult execute(LocalDate date, Long runId, boolean reset) {
        Map<String, Object> summary = new LinkedHashMap<>();
        List<PipelineStageRecord> stageRows = pipelineMapper.selectStages(runId);
        // stage_name → record
        Map<String, PipelineStageRecord> rowMap = new LinkedHashMap<>();
        for (PipelineStageRecord r : stageRows) {
            rowMap.put(r.getStageName(), r);
        }

        for (PipelineStage stage : ACTIVE_STAGES) {
            // 已终态的阶段跳过(DONE/IGNORED/SKIP)
            PipelineStageRecord row = rowMap.get(stage.name());
            if (row != null && isTerminal(row.getStatus())) {
                continue;
            }
            PipelineStageResult sr = executeStage(date, runId, stage);
            summary.put(stage.name(), sr.toMap());
            if ("FAILED".equals(sr.status) && stage.getPolicy() == FailurePolicy.HALT) {
                finish(runId, "FAILED", summary);
                log.error("[pipeline] date={} 阶段 {} 失败且策略 HALT,阻断下游", date, stage);
                return status(date.toString());
            }
        }
        finish(runId, "SUCCESS", summary);
        return status(date.toString());
    }

    private PipelineStageResult executeStage(LocalDate date, Long runId, PipelineStage stage) {
        long t0 = System.currentTimeMillis();
        PipelineStageResult result = new PipelineStageResult();
        result.stage = stage.name();
        try {
            // 1. seed(带回上游总数)
            SeedResult seed = stageSeeder.seed(stage, date, defaultSource);
            result.seededCount = seed.inserted();
            result.expectedTotal = seed.expectedTotal();
            result.taskIds = seed.taskIds();
            log.info("[pipeline] date={} stage={} seed inserted={} expectedTotal={}",
                    date, stage, seed.inserted(), seed.expectedTotal());

            // 2. 等待完成
            await(date, stage);

            // 3. 校验
            ValidateContext ctx = ValidateContext.of(seed.expectedTotal(), defaultSource, seed.taskIds());
            List<ValidateResult> checkResults = new ArrayList<>();
            for (PipelineValidator v : validators) {
                checkResults.add(v.validate(date, stage, ctx));
            }
            result.checkResults = checkResults;
            boolean allPassed = checkResults.stream().allMatch(ValidateResult::passed);
            result.status = allPassed ? "DONE" : "FAILED";
            result.actualTotal = actualTotal(checkResults);
            result.dupRows = dupRows(checkResults);
            result.lostRows = lostRows(checkResults);

            // 4. 写阶段结果(按 run_id+stage_name update,不新建行)
            persistStage(runId, stage.name(), result);
        } catch (Exception e) {
            log.error("[pipeline] date={} stage={} 异常: {}", date, stage, e.getMessage(), e);
            result.status = "FAILED";
            result.errorMsg = e.getMessage();
            persistStage(runId, stage.name(), result);
        }
        result.durationMs = System.currentTimeMillis() - t0;
        return result;
    }

    private void await(LocalDate date, PipelineStage stage) throws InterruptedException {
        long deadline = System.currentTimeMillis() + Duration.ofMinutes(awaitTimeoutMin).toMillis();
        while (System.currentTimeMillis() < deadline) {
            if (completionDetector.isComplete(date, stage, defaultSource)) {
                return;
            }
            long pending = completionDetector.pendingCount(date, stage, defaultSource);
            log.info("[pipeline] date={} stage={} 等待完成,剩余 PENDING/RETRY={}", date, stage, pending);
            Thread.sleep(Duration.ofSeconds(pollIntervalSec).toMillis());
        }
        long pending = completionDetector.pendingCount(date, stage, defaultSource);
        if (pending > 0) {
            throw new RuntimeException("阶段 " + stage + " 等待超时(" + awaitTimeoutMin + "min),仍剩 " + pending + " 未完成");
        }
    }

    private void persistStage(Long runId, String stageName, PipelineStageResult result) {
        try {
            PipelineStageRecord e = new PipelineStageRecord();
            e.setRunId(runId);
            e.setStageName(stageName);
            e.setStatus(result.status);
            e.setSeededCount(result.seededCount);
            e.setExpectedTotal(result.expectedTotal);
            e.setActualTotal(result.actualTotal);
            e.setDupRows(result.dupRows);
            e.setLostRows(result.lostRows);
            e.setDurationMs(result.durationMs);
            e.setCheckResult(toJson(result.checkResults));
            e.setErrorMsg(result.errorMsg);
            pipelineMapper.updateStageByName(e);
        } catch (Exception ex) {
            log.warn("[pipeline] 写阶段结果失败: {}", ex.getMessage());
        }
    }

    private void finish(Long runId, String runStatus, Map<String, Object> summary) {
        pipelineMapper.finishRun(runId, runStatus, toJson(Map.of("stages", summary,
                "finishedAt", LocalDateTime.now().toString())));
    }

    private boolean isTerminal(String status) {
        return "DONE".equals(status) || "IGNORED".equals(status) || "SKIP".equals(status);
    }

    private int actualTotal(List<ValidateResult> rs) { return rs.isEmpty() ? 0 : rs.get(0).actual(); }
    private int dupRows(List<ValidateResult> rs) { return rs.isEmpty() ? 0 : rs.get(0).dupRows(); }
    private int lostRows(List<ValidateResult> rs) { return rs.isEmpty() ? 0 : rs.get(0).lostRows(); }

    private static final com.fasterxml.jackson.databind.ObjectMapper OM =
            new com.fasterxml.jackson.databind.ObjectMapper();

    static String toJson(Object o) {
        try {
            return OM.writeValueAsString(o);
        } catch (Exception e) {
            return String.valueOf(o);
        }
    }
}
