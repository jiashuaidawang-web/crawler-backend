package com.dunwugudao.crawler.admin.pipeline;

import com.dunwugudao.crawler.persistence.entity.PipelineRun;
import com.dunwugudao.crawler.persistence.entity.PipelineStageRecord;
import com.dunwugudao.crawler.persistence.entity.CrawlAlert;
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
    private final CrawlAlertMapper crawlAlertMapper;
    private final SeedGenerator seedGenerator;
    private final StageCompletionDetector completionDetector;
    private final List<PipelineValidator> validators;
    private final StageSeeder stageSeeder;
    private final org.springframework.jdbc.core.JdbcTemplate chJdbc;

    @Value("${crawler.pipeline.source:1}")
    private int defaultSource;

    @Value("${crawler.pipeline.await-timeout-min:60}")
    private int awaitTimeoutMin;

    @Value("${crawler.pipeline.poll-interval-sec:30}")
    private int pollIntervalSec;

    @Value("${crawler.pipeline.weekly-day-of-week:6}")
    private int weeklyRunDayConfig; // 6=周六,周级阶段(如 stock_weekly)仅此日跑

    public DailyPipelineOrchestrator(PipelineMapper pipelineMapper,
                                    CrawlAlertMapper crawlAlertMapper,
                                    SeedGenerator seedGenerator,
                                    StageCompletionDetector completionDetector,
                                    List<PipelineValidator> validators,
                                    StageSeeder stageSeeder,
                                    @org.springframework.beans.factory.annotation.Qualifier("chJdbcTemplate") org.springframework.jdbc.core.JdbcTemplate chJdbc) {
        this.pipelineMapper = pipelineMapper;
        this.crawlAlertMapper = crawlAlertMapper;
        this.seedGenerator = seedGenerator;
        this.completionDetector = completionDetector;
        this.validators = validators;
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

        // 链式阶段(依赖前置阶段落库后,从 CK 表读 ID 再下发)
        for (PipelineStage stage : CHAIN_STAGES) {
            PipelineStageRecord row = rowMap.get(stage.name());
            if (row != null && isTerminal(row.getStatus())) {
                continue;
            }
            PipelineStageResult sr = executeChainStage(date, runId, stage);
            summary.put(stage.name(), sr.toMap());
        }

        // 周级阶段(仅每周指定日跑)
        if (isWeeklyRunDay(date)) {
            for (PipelineStage stage : WEEKLY_STAGES) {
                PipelineStageRecord row = rowMap.get(stage.name());
                if (row != null && isTerminal(row.getStatus())) {
                    continue;
                }
                PipelineStageResult sr = executeWeeklyStage(date, runId, stage);
                summary.put(stage.name(), sr.toMap());
            }
        }

        finish(runId, "SUCCESS", summary);
        return status(date.toString());
    }

    /** 判断是否为周跑日(默认周六,可通过 crawler.pipeline.weekly-day-of-week 配置,1=周一~7=周日)。 */
    private boolean isWeeklyRunDay(LocalDate date) {
        int configDay = weeklyRunDayConfig;
        int dayOfWeek = date.getDayOfWeek().getValue(); // 1=Mon, 7=Sun
        return dayOfWeek == configDay;
    }

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

    /** 周级阶段:聚合类任务(如 stock_weekly 从日K聚合)。 */
    private PipelineStageResult executeWeeklyStage(LocalDate date, Long runId, PipelineStage stage) {
        long t0 = System.currentTimeMillis();
        PipelineStageResult result = new PipelineStageResult();
        result.stage = stage.name();
        try {
            SeedResult seed = stageSeeder.seed(stage, date, defaultSource);
            result.seededCount = seed.inserted();
            result.expectedTotal = seed.expectedTotal();
            result.taskIds = seed.taskIds();
            log.info("[pipeline] date={} weekly stage={} result={}", date, stage, seed.message());

            // 周级阶段通常是聚合(无异步 worker),直接完成;若有 task 则等待
            if (seed.inserted() > 0 && !stage.getTaskTypes().isEmpty()) {
                await(date, stage);
            }

            ValidateContext ctx = ValidateContext.of(seed.expectedTotal(), defaultSource, seed.taskIds());
            List<ValidateResult> checkResults = new ArrayList<>();
            for (PipelineValidator v : validators) {
                checkResults.add(v.validate(date, stage, ctx));
            }
            result.checkResults = checkResults;
            boolean allPassed = checkResults.stream().allMatch(ValidateResult::passed);
            result.status = allPassed ? "DONE" : "FAILED";
            result.actualTotal = actualTotal(checkResults);
            if (!allPassed) {
                writeAlert(date, stage, result, checkResults);
            }
            persistStage(runId, stage.name(), result);
        } catch (Exception e) {
            log.error("[pipeline] date={} weekly stage={} 异常: {}", date, stage, e.getMessage(), e);
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
