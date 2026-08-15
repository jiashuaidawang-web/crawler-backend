package com.dunwugudao.crawler.admin.pipeline;

import com.dunwugudao.crawler.persistence.entity.PipelineRun;
import com.dunwugudao.crawler.persistence.entity.PipelineStageRecord;
import com.dunwugudao.crawler.persistence.entity.CrawlAlert;
import com.dunwugudao.crawler.persistence.mapper.CrawlTaskMapper;
import com.dunwugudao.crawler.persistence.mapper.PipelineMapper;
import com.dunwugudao.crawler.persistence.mapper.CrawlAlertMapper;
import com.dunwugudao.crawler.admin.seed.SeedGenerator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.math.BigDecimal;
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

    /** 活跃阶段(收盘后全量,按依赖排序)。 */
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
            PipelineStage.DRAGON_TIGER,
            PipelineStage.BOARD_BASIC,
            PipelineStage.STOCK_BY_BOARD,
            PipelineStage.STOCK_WEEKLY
    );

    /** 链式阶段:依赖前置阶段落库后,从 CK 表读 ID 再下发。 */
    private static final List<PipelineStage> CHAIN_STAGES = List.of(
            PipelineStage.DRAGON_TIGER_DETAIL
    );

    private final PipelineMapper pipelineMapper;
    private final CrawlTaskMapper crawlTaskMapper;
    private final CrawlAlertMapper crawlAlertMapper;
    private final SeedGenerator seedGenerator;
    private final StageCompletionDetector completionDetector;
    private final List<PipelineValidator> validators;
    private final TotalCountValidator totalCountValidator;
    private final StageSeeder stageSeeder;
    private final org.springframework.jdbc.core.JdbcTemplate chJdbc;

    @Value("${crawler.pipeline.source:1}")
    private int defaultSource;

    @Value("${crawler.pipeline.await-timeout-min:60}")
    private int awaitTimeoutMin;

    @Value("${crawler.pipeline.poll-interval-sec:30}")
    private int pollIntervalSec;

    public DailyPipelineOrchestrator(PipelineMapper pipelineMapper,
                                    CrawlTaskMapper crawlTaskMapper,
                                    CrawlAlertMapper crawlAlertMapper,
                                    SeedGenerator seedGenerator,
                                    StageCompletionDetector completionDetector,
                                    List<PipelineValidator> validators,
                                    TotalCountValidator totalCountValidator,
                                    StageSeeder stageSeeder,
                                    @org.springframework.beans.factory.annotation.Qualifier("chJdbcTemplate") org.springframework.jdbc.core.JdbcTemplate chJdbc) {
        this.pipelineMapper = pipelineMapper;
        this.crawlTaskMapper = crawlTaskMapper;
        this.crawlAlertMapper = crawlAlertMapper;
        this.seedGenerator = seedGenerator;
        this.completionDetector = completionDetector;
        this.validators = validators;
        this.totalCountValidator = totalCountValidator;
        this.stageSeeder = stageSeeder;
        this.chJdbc = chJdbc;
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

    /**
     * 强制重跑:reset 已有的 SUCCESS/RUNNING run 后重新执行(用于补数据/重跑)。
     * <p>不走 {@link #ensureRun} 的幂等短路,直接 reset stage + run 状态后调 {@link #execute}。
     * 同时重置该日期下 DEAD/FAILED 的任务为 PENDING,让 worker 能重新认领(seed 幂等会跳过已存在的任务,
     * 但这些任务状态已是终态无法被认领,需先重置)。</p>
     */
    public PipelineRunResult run(String dateStr, boolean force) {
        LocalDate date = LocalDate.parse(dateStr);
        if (force) {
            // 重置 DEAD/FAILED 任务为 PENDING,使 seed 幂等跳过后的任务仍能被 worker 认领
            int resetCount = crawlTaskMapper.forceResetDeadTasks(dateStr + "%");
            if (resetCount > 0) {
                log.info("[run-pipeline] force=true, reset {} DEAD/FAILED tasks to PENDING for date={}",
                        resetCount, dateStr);
            }
            PipelineRun existing = pipelineMapper.selectRunByDate(date);
            if (existing != null) {
                log.info("[run-pipeline] force=true, resetting existing run(status={}) for date={}",
                        existing.getStatus(), dateStr);
                pipelineMapper.resetStagesToPending(existing.getRunId());
                pipelineMapper.resetRunToRunning(existing.getRunId());
                initStages(existing.getRunId());
                return execute(date, existing.getRunId(), false);
            }
        }
        return run(dateStr);
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

        // 已 HALT 失败的阶段集合(用于阻断其下游依赖,独立阶段不受影响)
        Set<String> haltFailedStages = new java.util.HashSet<>();

        for (PipelineStage stage : ACTIVE_STAGES) {
            // 已终态的阶段跳过(DONE/IGNORED/SKIP)
            PipelineStageRecord row = rowMap.get(stage.name());
            if (row != null && isTerminal(row.getStatus())) {
                continue;
            }
            // 依赖的阶段已 HALT 失败 → 跳过本阶段(阻断),但继续执行其他独立阶段
            if (isBlockedByHalt(stage, haltFailedStages)) {
                PipelineStageResult skipped = new PipelineStageResult();
                skipped.stage = stage.name();
                skipped.status = "HALT_SKIPPED";
                skipped.errorMsg = "前置阶段 HALT 失败,阻断本阶段";
                summary.put(stage.name(), skipped.toMap());
                persistStage(runId, stage.name(), skipped);
                log.warn("[pipeline] date={} stage={} 因前置 HALT 失败被阻断,跳过", date, stage.name());
                continue;
            }
            PipelineStageResult sr = executeStage(date, runId, stage);
            summary.put(stage.name(), sr.toMap());
            if ("FAILED".equals(sr.status) && stage.getPolicy() == FailurePolicy.HALT) {
                haltFailedStages.add(stage.name());
                log.error("[pipeline] date={} 阶段 {} 失败且策略 HALT,阻断其依赖的下游", date, stage);
            }
        }

        // 链式阶段(依赖前置阶段落库后,从 CK 表读 ID 再下发)
        for (PipelineStage stage : CHAIN_STAGES) {
            PipelineStageRecord row = rowMap.get(stage.name());
            if (row != null && isTerminal(row.getStatus())) {
                continue;
            }
            if (isBlockedByHalt(stage, haltFailedStages)) {
                PipelineStageResult skipped = new PipelineStageResult();
                skipped.stage = stage.name();
                skipped.status = "HALT_SKIPPED";
                skipped.errorMsg = "前置阶段 HALT 失败,阻断本阶段";
                summary.put(stage.name(), skipped.toMap());
                persistStage(runId, stage.name(), skipped);
                log.warn("[pipeline] date={} chain stage={} 因前置 HALT 失败被阻断,跳过", date, stage.name());
                continue;
            }
            PipelineStageResult sr = executeChainStage(date, runId, stage);
            summary.put(stage.name(), sr.toMap());
        }

        // run 终态:有 HALT 失败→FAILED,否则 SUCCESS
        boolean anyHaltFailed = !haltFailedStages.isEmpty();
        finish(runId, anyHaltFailed ? "FAILED" : "SUCCESS", summary);
        return status(date.toString());
    }

    /** 判断是否为周跑日(默认周六,可通过 crawler.pipeline.weekly-day-of-week 配置,1=周一~7=周日)。 */
    /** 记录当日股票数+板块数到 check_result,供明天 STOCK_BY_BOARD 比较(相同则跳过省 IP)。 */
    private void storeBoardRelCounts(LocalDate date, int source, PipelineStageResult result) {
        try {
            Integer stockObj = chJdbc.queryForObject(
                    "SELECT count(DISTINCT ts_code) FROM stock_daily WHERE trade_date = ? AND data_source = ?",
                    Integer.class, date.toString(), source);
            result.boardRelStockCount = stockObj == null ? 0 : stockObj;

            Integer boardObj = seedGenerator.queryBoardCodeCount();
            result.boardRelBoardCount = boardObj == null ? 0 : boardObj;
            log.info("[pipeline] date={} STOCK_BY_BOARD 记录数量 stock={} board={}",
                    date, result.boardRelStockCount, result.boardRelBoardCount);
        } catch (Exception e) {
            log.warn("[pipeline] 记录板块关联数量失败: {}", e.getMessage());
        }
    }
    private PipelineStageResult executeChainStage(LocalDate date, Long runId, PipelineStage stage) {
        long t0 = System.currentTimeMillis();
        PipelineStageResult result = new PipelineStageResult();
        result.stage = stage.name();
        try {
            int inserted = 0;
            if (stage == PipelineStage.DRAGON_TIGER_DETAIL) {
                inserted = seedGenerator.chainDragonTigerDetails(date.toString());
            } else {
                result.status = "SKIP";
                result.errorMsg = "未知链式阶段";
                return result;
            }
            result.seededCount = inserted;
            result.expectedTotal = Math.max(inserted, 0); // 链式阶段无独立上游总数,以实际下发数为期
            log.info("[pipeline] date={} chain stage={} inserted={}", date, stage, inserted);

            // 等待完成
            await(date, stage);

            // 校验(dt_detail 无独立上游总数,仅做基础量校验)
            ValidateContext ctx = ValidateContext.of(0, defaultSource, List.of());
            List<ValidateResult> checkResults = new ArrayList<>();
            for (PipelineValidator v : validators) {
                checkResults.add(v.validate(date, stage, ctx));
            }
            result.checkResults = checkResults;
            result.status = "DONE";
            persistStage(runId, stage.name(), result);
        } catch (Exception e) {
            log.error("[pipeline] date={} chain stage={} 异常: {}", date, stage, e.getMessage(), e);
            result.status = "FAILED";
            result.errorMsg = e.getMessage();
            persistStage(runId, stage.name(), result);
        }
        result.durationMs = System.currentTimeMillis() - t0;
        return result;
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

            // STOCK_BY_BOARD:记录今日股票数+板块数供明天比较(数量未变则跳过省 IP)
            if (stage == PipelineStage.STOCK_BY_BOARD) {
                storeBoardRelCounts(date, defaultSource, result);
            }

            // 1.5 捕获基线行数(seed 后、爬取前)→validate 时只计本次新增,避免历史数据干扰
            int baselineRows = totalCountValidator.baselineRows(stage, date, defaultSource);
            result.baselineRows = baselineRows;
            log.info("[pipeline] date={} stage={} baselineRows={}", date, stage, baselineRows);

            // 2. 等待完成
            await(date, stage);

            // 3. 校验(传入 baselineRows)
            ValidateContext ctx = ValidateContext.of(seed.expectedTotal(), defaultSource, seed.taskIds(), baselineRows);
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

            // 校验失败 → 写 crawl_alert(补 trade_date)
            if (!allPassed) {
                writeAlert(date, stage, result, checkResults);
            }

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

    /** 校验失败时写告警(补 trade_date 等字段)。 */
    private void writeAlert(LocalDate date, PipelineStage stage, PipelineStageResult result,
                            List<ValidateResult> checkResults) {
        try {
            ValidateResult failed = checkResults.stream().filter(r -> !r.passed()).findFirst().orElse(null);
            if (failed == null) {
                return;
            }
            int lost = result.lostRows;
            String severity = lost > 0 ? "ERROR" : "WARN";
            String message = String.format("[%s] %s", stage.name(), failed.message());

            CrawlAlert alert = new CrawlAlert();
            alert.setAlertType("VOLUME_DEVIATION");
            alert.setTaskType(stage.name());
            alert.setTradeDate(date);
            alert.setSource(defaultSource);
            alert.setSeverity(severity);
            alert.setMessage(message);
            alert.setValueActual(BigDecimal.valueOf(result.actualTotal));
            alert.setValueExpected(BigDecimal.valueOf(result.expectedTotal));
            alert.setResolved(0);
            crawlAlertMapper.insert(alert);
            log.warn("[pipeline] 校验失败告警已写入: {} {} 丢失{}行", date, stage.name(), lost);
        } catch (Exception e) {
            log.warn("[pipeline] 写告警失败: {}", e.getMessage());
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
            e.setCheckResult(buildCheckResultJson(result));
            e.setErrorMsg(result.errorMsg);
            pipelineMapper.updateStageByName(e);
        } catch (Exception ex) {
            log.warn("[pipeline] 写阶段结果失败: {}", ex.getMessage());
        }
    }

    /** 构建 check_result JSON,STOCK_BY_BOARD 时追加股票数+板块数供明天比较。 */
    private String buildCheckResultJson(PipelineStageResult result) {
        String base = toJson(result.checkResults);
        if ("STOCK_BY_BOARD".equals(result.stage) && (result.boardRelStockCount > 0 || result.boardRelBoardCount > 0)) {
            // 在 JSON 数组末尾追加数量对象
            if (base.endsWith("]")) {
                base = base.substring(0, base.length() - 1)
                        + (base.length() > 1 ? "," : "") + "{\"stockCount\":" + result.boardRelStockCount
                        + ",\"boardCount\":" + result.boardRelBoardCount + "}]";
            }
        }
        return base;
    }

    private void finish(Long runId, String runStatus, Map<String, Object> summary) {
        pipelineMapper.finishRun(runId, runStatus, toJson(Map.of("stages", summary,
                "finishedAt", LocalDateTime.now().toString())));
    }

    private boolean isTerminal(String status) {
        return "DONE".equals(status) || "IGNORED".equals(status) || "SKIP".equals(status)
                || "HALT_SKIPPED".equals(status);
    }

    /**
     * 判断阶段是否被上游 HALT 失败阻断(直接 or 传递依赖)。
     * <p>遍历 stage 的 dependsOn 链,若任一依赖(或传递依赖)在 haltFailedStages 中则阻断。</p>
     */
    private boolean isBlockedByHalt(PipelineStage stage, Set<String> haltFailedStages) {
        Set<PipelineStage> visited = new java.util.HashSet<>();
        Deque<PipelineStage> stack = new java.util.ArrayDeque<>(stage.getDependsOn());
        while (!stack.isEmpty()) {
            PipelineStage dep = stack.pop();
            if (!visited.add(dep)) {
                continue;
            }
            if (haltFailedStages.contains(dep.name())) {
                return true;
            }
            stack.addAll(dep.getDependsOn());
        }
        return false;
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
