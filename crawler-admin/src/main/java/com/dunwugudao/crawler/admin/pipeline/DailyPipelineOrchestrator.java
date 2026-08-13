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
 * <p>幂等:同日已有 RUNNING/SUCCESS 的 run 则直接返回 resume 点;启动时 abort 旧 RUNNING。
 * 断点续跑:resume() 从首个非 DONE/IGNORED 阶段开始。</p>
 *
 * <p>Phase 1 仅接入 STOCK_DAILY 单阶段验证编排骨架;其余阶段 Phase 2 按需接入。</p>
 */
@Service
public class DailyPipelineOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(DailyPipelineOrchestrator.class);

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

    /** 幂等入口:跑全日批。 */
    public PipelineRunResult run(String dateStr) {
        LocalDate date = LocalDate.parse(dateStr);
        PipelineRun existing = pipelineMapper.selectRunByDate(date);
        if (existing != null && ("SUCCESS".equals(existing.getStatus()) || "RUNNING".equals(existing.getStatus()))) {
            return status(dateStr); // 已成功或在跑,直接返回当前状态
        }
        // abort 旧 RUNNING,新建
        pipelineMapper.abortStaleRuns(date);
        PipelineRun run = new PipelineRun();
        run.setRunDate(date);
        pipelineMapper.insertRunIgnoreConflict(run);
        Long runId = pipelineMapper.selectRunIdByDate(date);
        if (runId == null) {
            return status(dateStr); // 并发抢到,返回现有
        }
        return execute(date, runId, List.of(PipelineStage.STOCK_DAILY)); // Phase 1:仅 STOCK_DAILY
    }

    /** 断点续跑:从首个非终态阶段开始。 */
    public PipelineRunResult resume(String dateStr) {
        LocalDate date = LocalDate.parse(dateStr);
        PipelineRun run = pipelineMapper.selectRunByDate(date);
        if (run == null) {
            return run(dateStr); // 无 run 则全新跑
        }
        List<PipelineStageRecord> stages = pipelineMapper.selectStages(run.getRunId());
        List<PipelineStage> toRun = new ArrayList<>();
        for (PipelineStageRecord s : stages) {
            if (!isTerminal(s.getStatus())) {
                toRun.add(Enum.valueOf(PipelineStage.class, s.getStageName()));
            }
        }
        if (toRun.isEmpty()) {
            return status(dateStr);
        }
        return execute(date, run.getRunId(), toRun);
    }

    /** 查当前状态。 */
    public PipelineRunResult status(String dateStr) {
        LocalDate date = LocalDate.parse(dateStr);
        PipelineRun run = pipelineMapper.selectRunByDate(date);
        if (run == null) {
            return new PipelineRunResult(dateStr, "NONE", List.of(), "尚未发起跑批");
        }
        List<PipelineStageRecord> stages = pipelineMapper.selectStages(run.getRunId());
        return new PipelineRunResult(dateStr, run.getStatus(), stages, run.getSummary());
    }

    // ---------------- 核心执行 ----------------

    private PipelineRunResult execute(LocalDate date, Long runId, List<PipelineStage> stages) {
        Map<String, Object> summary = new LinkedHashMap<>();
        long runStart = System.currentTimeMillis();
        for (PipelineStage stage : stages) {
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
            // 1. seed
            PipelineStageRecord entity = new PipelineStageRecord();
            entity.setRunId(runId);
            entity.setStageName(stage.name());
            entity.setSeq(stage.getSeq());
            pipelineMapper.insertStage(entity);

            SeedResult seed = stageSeeder.seed(stage, date, defaultSource);
            result.seededCount = seed.inserted();
            result.expectedTotal = seed.expectedTotal();
            result.taskIds = seed.taskIds();
            log.info("[pipeline] date={} stage={} seed inserted={} expectedTotal={}",
                    date, stage, seed.inserted(), seed.expectedTotal());

            // 2. 等待完成
            await(date, stage, entity.getStageId());

            // 3. 校验
            ValidateContext ctx = ValidateContext.of(seed.expectedTotal(), seed.taskIds());
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

            // 4. 写阶段结果
            persistStage(entity.getStageId(), result, System.currentTimeMillis() - t0);
        } catch (Exception e) {
            log.error("[pipeline] date={} stage={} 异常: {}", date, stage, e.getMessage(), e);
            result.status = "FAILED";
            result.errorMsg = e.getMessage();
            persistStage(null, result, System.currentTimeMillis() - t0);
        }
        result.durationMs = System.currentTimeMillis() - t0;
        return result;
    }

    private void await(LocalDate date, PipelineStage stage, Long stageId) throws InterruptedException {
        long deadlineGuard = System.currentTimeMillis() + Duration.ofMinutes(awaitTimeoutMin).toMillis();
        while (System.currentTimeMillis() < deadlineGuard) {
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

    private void persistStage(Long stageId, PipelineStageResult result, long durationMs) {
        try {
            PipelineStageRecord e = new PipelineStageRecord();
            e.setStageId(stageId);
            e.setStatus(result.status);
            e.setSeededCount(result.seededCount);
            e.setExpectedTotal(result.expectedTotal);
            e.setActualTotal(result.actualTotal);
            e.setDupRows(result.dupRows);
            e.setLostRows(result.lostRows);
            e.setDurationMs(durationMs);
            e.setCheckResult(toJson(result.checkResults));
            e.setErrorMsg(result.errorMsg);
            pipelineMapper.updateStage(e);
        } catch (Exception ex) {
            log.warn("[pipeline] 写阶段结果失败: {}", ex.getMessage());
        }
    }

    private void finish(Long runId, String status, Map<String, Object> summary) {
        pipelineMapper.finishRun(runId, status, toJson(Map.of("stages", summary,
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
