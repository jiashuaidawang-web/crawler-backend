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

import jakarta.annotation.PostConstruct;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.math.BigDecimal;
import java.util.*;
import java.util.stream.Stream;

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
            PipelineStage.LIMIT_UP,
            PipelineStage.LIMIT_DOWN,
            PipelineStage.LIMIT_ZHABAN,
            PipelineStage.STRONG_POOL,
            PipelineStage.CIXIN_POOL,
            PipelineStage.NORTHBOUND,
            PipelineStage.INDEX_DAILY,
            PipelineStage.DRAGON_TIGER,
            PipelineStage.DRAGON_TIGER_DETAIL,
            PipelineStage.BOARD_BASIC,
            // STOCK_BY_BOARD 暂时下线:全量探测板块-个股关联消耗 IP 过大(每个板块 1 次探测),暂停以节省 IP
            // PipelineStage.STOCK_BY_BOARD,
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
    private final org.springframework.jdbc.core.JdbcTemplate pgJdbc;  // openGauss(操作型库)

    @Value("${crawler.pipeline.source:1}")
    private int defaultSource;

    @Value("${crawler.pipeline.await-timeout-min:60}")
    private int awaitTimeoutMin;

    @Value("${crawler.pipeline.poll-interval-sec:30}")
    private int pollIntervalSec;

    /** 测试阶段开启:实时查 CK 覆盖 actualTotal,确保数据准确(上线后可关闭走存储值)。 */
    @Value("${crawler.pipeline.realtime-count:true}")
    private boolean realtimeCount;

    public DailyPipelineOrchestrator(PipelineMapper pipelineMapper,
                                    CrawlTaskMapper crawlTaskMapper,
                                    CrawlAlertMapper crawlAlertMapper,
                                    SeedGenerator seedGenerator,
                                    StageCompletionDetector completionDetector,
                                    List<PipelineValidator> validators,
                                    TotalCountValidator totalCountValidator,
                                    StageSeeder stageSeeder,
                                    @org.springframework.beans.factory.annotation.Qualifier("chJdbcTemplate") org.springframework.jdbc.core.JdbcTemplate chJdbc,
                                    @org.springframework.beans.factory.annotation.Qualifier("pgJdbcTemplate") org.springframework.jdbc.core.JdbcTemplate pgJdbc) {
        this.pipelineMapper = pipelineMapper;
        this.crawlTaskMapper = crawlTaskMapper;
        this.crawlAlertMapper = crawlAlertMapper;
        this.seedGenerator = seedGenerator;
        this.completionDetector = completionDetector;
        this.validators = validators;
        this.totalCountValidator = totalCountValidator;
        this.stageSeeder = stageSeeder;
        this.chJdbc = chJdbc;
        this.pgJdbc = pgJdbc;
    }

    /** 启动时清理进程上次遗留的 RUNNING(已中断,不可能还在跑),统一标 ABORTED 打破卡死。
     *  DB 瞬时不可达时只告警,不阻断启动(避免一次抖动导致 admin 起不来)。 */
    @PostConstruct
    public void abortStaleRunsOnStartup() {
        try {
            int n = pipelineMapper.abortAllStaleRuns();
            if (n > 0) {
                log.warn("[pipeline] 启动清理:已将 {} 条陈旧 RUNNING run 标为 ABORTED", n);
            }
        } catch (Exception e) {
            log.warn("[pipeline] 启动清理陈旧 run 失败(下次重跑会复用/重置): {}", e.getMessage());
        }
    }

    /**
     * 预览各阶段预期数据量(不实际跑批):对每个活跃阶段执行 seed 获取 expectedTotal,
     * 返回 stageName → {displayName, expectedTotal, message}。
     */
    public Map<String, Object> preview(String dateStr) {
        LocalDate date = LocalDate.parse(dateStr);
        Map<String, Object> previewMap = new LinkedHashMap<>();
        for (PipelineStage stage : ACTIVE_STAGES) {
            try {
                SeedResult seed = stageSeeder.seed(stage, date, defaultSource);
                Map<String, Object> info = new LinkedHashMap<>();
                info.put("displayName", stage.getDisplayName());
                info.put("expectedTotal", seed.expectedTotal());
                info.put("message", seed.message());
                previewMap.put(stage.name(), info);
            } catch (Exception e) {
                Map<String, Object> info = new LinkedHashMap<>();
                info.put("displayName", stage.getDisplayName());
                info.put("expectedTotal", 0);
                info.put("message", "预览失败: " + e.getMessage());
                previewMap.put(stage.name(), info);
            }
        }
        return previewMap;
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
            // 重置该日全部已执行任务(含 SUCCESS),否则 seed 幂等跳过已成功任务,数据不会重抓
            int resetCount = crawlTaskMapper.forceResetAllTasksForDate(dateStr);
            if (resetCount > 0) {
                log.info("[run-pipeline] force=true, reset {} tasks to PENDING for date={}",
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
        return buildResultWithMessage(dateStr, run);
    }

    /** 查指定 runId 的详情(用于历史 run)。 */
    public PipelineRunResult statusByRunId(String dateStr, Long runId) {
        PipelineRun run = pipelineMapper.selectRunById(runId);
        if (run == null) {
            return new PipelineRunResult(dateStr, "NONE", List.of(), "该跑批不存在");
        }
        return buildResultWithMessage(dateStr, run);
    }

    /** 列出某日所有 run(历史)。 */
    public List<PipelineRun> history(String dateStr) {
        LocalDate date = LocalDate.parse(dateStr);
        return pipelineMapper.selectRunsByDate(date);
    }

    /** 组装结果 + 运行时生成「人话摘要」+ 可选实时 CK 重算校验结果。 */
    private PipelineRunResult buildResultWithMessage(String dateStr, PipelineRun run) {
        LocalDate date = LocalDate.parse(dateStr);
        List<PipelineStageRecord> stages = pipelineMapper.selectStages(run.getRunId());
        for (PipelineStageRecord s : stages) {
            // 中文名
            try {
                s.setDisplayName(PipelineStage.valueOf(s.getStageName()).getDisplayName());
            } catch (IllegalArgumentException e) {
                s.setDisplayName(s.getStageName());
            }
            // 测试阶段:实时查 CK 重算 actualTotal/lostRows/dupRows/status
            if (realtimeCount) {
                recomputeStageValidation(s, date);
            }
            s.setUserMessage(summarize(s));
        }
        return new PipelineRunResult(dateStr, run.getStatus(), stages, run.getSummary());
    }

    /** 实时查 CK 重算阶段的校验结果(actualTotal/lostRows/dupRows/status)。 */
    private void recomputeStageValidation(PipelineStageRecord s, LocalDate date) {
        PipelineStage stage;
        try {
            stage = PipelineStage.valueOf(s.getStageName());
        } catch (IllegalArgumentException e) {
            return;
        }
        int expected = s.getExpectedTotal() == null ? 0 : s.getExpectedTotal();
        if (expected <= 0) {
            return;  // 无上游总数,跳过
        }
        // 实时查 CK —— 只统计本次 run 产出(create_date=当天),避免历史存量行数干扰校验
        int actual = totalCountValidator.countActualByStage(s.getStageName(), date, defaultSource, true);
        s.setActualTotal(actual);
        if (actual >= expected) {
            // PASS
            s.setStatus("DONE");
            s.setLostRows(0);
            s.setDupRows(0);
        } else {
            // actual < expected:查 dupGroups 判定
            int dupGroups = totalCountValidator.countDupGroups(stage, date, defaultSource, true);
            s.setDupRows(dupGroups);
            int diff = expected - actual;
            if (diff <= dupGroups) {
                s.setStatus("DONE");
                s.setLostRows(0);
            } else {
                s.setStatus("FAILED");
                s.setLostRows(diff - dupGroups);
            }
        }
    }

    // ---------------- 人话摘要 ----------------

    /**
     * 给单个阶段生成「人话摘要」+ 操作建议。
     * 规则按优先级:空跑跳过 → 失败(有丢失/无丢失) → 成功(有重复/无重复) → 兜底。
     */
    String summarize(PipelineStageRecord s) {
        String status = s.getStatus();
        int seeded = s.getSeededCount() == null ? 0 : s.getSeededCount();
        int expected = s.getExpectedTotal() == null ? 0 : s.getExpectedTotal();
        int actual = s.getActualTotal() == null ? 0 : s.getActualTotal();
        int lost = s.getLostRows() == null ? 0 : s.getLostRows();
        int dup = s.getDupRows() == null ? 0 : s.getDupRows();

        // 空跑:没下任务 且 没有实际数据
        if (seeded == 0 && actual == 0 && !"RUNNING".equals(status)) {
            return "未下发任务,未产生数据。如需数据请「一键跑批」。";
        }
        // 有数据但没下任务 → 历史数据(非本次 run 产出)
        if (seeded == 0 && actual > 0 && !"RUNNING".equals(status)) {
            if ("DONE".equals(status)) {
                return String.format("有历史数据 %d 行(非本次 run 产出),数据量正常。", actual);
            }
            return String.format("有历史数据 %d 行(非本次 run 产出),期望 %d 行,丢失 %d 行。", actual, expected, lost);
        }
        // 失败
        if ("FAILED".equals(status)) {
            if (lost > 0) {
                return String.format("校验失败:期望 %d 行,实际写入 %d 行,丢失 %d 行。点「修复数据」重抓该阶段。", expected, actual, lost);
            }
            if (actual > expected && expected > 0) {
                return String.format("校验失败:实际 %d 行超过期望 %d 行,可能存在重复口径。建议确认上游数据源口径。", actual, expected);
            }
            return "校验失败。点「修复数据」重抓该阶段。";
        }
        // 成功
        if ("DONE".equals(status) || "SUCCESS".equals(status)) {
            if (actual > 0 && expected > 0) {
                int diff = actual - expected;
                if (Math.abs(diff) <= Math.max(1, expected / 20)) {  // 偏差 ≤5% 视为正常
                    return String.format("完成。期望 %d 行,实际 %d 行,偏差在正常范围。", expected, actual);
                }
                if (diff < 0) {
                    return String.format("完成。期望 %d 行,实际 %d 行(少 %d 行,可被重复组解释)。", expected, actual, -diff);
                }
                return String.format("完成。期望 %d 行,实际 %d 行(多 %d 行),请确认口径。", expected, actual, diff);
            }
            if (dup > 0) {
                return String.format("完成。写入 %d 行,存在 %d 组重复。", actual, dup);
            }
            return String.format("完成。写入 %d 行。", actual);
        }
        // 运行中 / 等待中 / 跳过
        if ("RUNNING".equals(status)) {
            return "正在执行中...";
        }
        if ("PENDING".equals(status)) {
            return "等待上游阶段完成后执行。";
        }
        if ("SKIP".equals(status) || "IGNORED".equals(status)) {
            return "已跳过。";
        }
        return "";
    }

    // ---------------- run 初始化 ----------------

    /**
     * 确保某日有 RUNNING 的 run(并预建 stage 行)。
     *
     * <p>幂等策略(受 uq_pipeline_run_date 唯一约束,每天只容一条 run):</p>
     * <ul>
     *   <li>无 run → 新建(用 insertRunIgnoreConflict 防并发冲突)</li>
     *   <li>RUNNING/SUCCESS → 返回 null(已在跑或已完成,无需执行)</li>
     *   <li>FAILED/ABORTED → 原地 reset 重跑(不能新建,会违反唯一约束)</li>
     * </ul>
     *
     * @return runId(已有 RUNNING/SUCCESS 时返回 null,表示无需执行)
     */
    private Long ensureRun(LocalDate date, boolean resetIfExists) {
        PipelineRun latest = pipelineMapper.selectRunByDate(date);
        if (latest != null) {
            if ("RUNNING".equals(latest.getStatus()) || "SUCCESS".equals(latest.getStatus())) {
                return null; // 幂等:已在跑或已完成
            }
            // FAILED/ABORTED → 原地 reset 重跑。表有 uq_pipeline_run_date,每天只能一条 run,
            // 不能 insert 新行(会 DuplicateKeyException),只能在原 run 上重置。
            if (resetIfExists) {
                pipelineMapper.resetStagesToPending(latest.getRunId());
                pipelineMapper.resetRunToRunning(latest.getRunId());
            }
            initStages(latest.getRunId());
            return latest.getRunId();
        }
        // 该日期首次跑:新建 run(ignore-conflict 防并发插入冲突)
        PipelineRun r = new PipelineRun();
        r.setRunDate(date);
        pipelineMapper.insertRunIgnoreConflict(r);
        // insert 可能因并发被忽略,统一按日期重新查出 runId
        PipelineRun created = pipelineMapper.selectRunByDate(date);
        if (created == null) {
            return null;
        }
        initStages(created.getRunId());
        return created.getRunId();
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
        // 兜底:run 存在但 stage 行缺失(上次 initStages 失败/进程中断),补建后重读,
        // 避免整轮 execute 白跑且 persistStage UPDATE 0 行。
        if (stageRows.isEmpty()) {
            log.warn("[pipeline] date={} runId={} 无 stage 行,兜底补建", date, runId);
            initStages(runId);
            stageRows = pipelineMapper.selectStages(runId);
        }
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

            // 2. 等待完成
            await(date, stage);

            // 3. 校验(实时查 CK,对比 expected)
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

    // ---------------- 阶段诊断 ----------------

    /**
     * 诊断失败阶段:判断是"admin 未完整下发"、"task 执行失败"还是"引擎去重/口径差异"。
     *
     * <p>逻辑:今天 task 数 >= 历史平均 × 0.8 视为下发完整,否则为 seed 失败。</p>
     */
    public java.util.Map<String, Object> diagnoseStage(String dateStr, String stageName) {
        LocalDate date = LocalDate.parse(dateStr);
        PipelineStage stage = Stream.of(PipelineStage.values())
                .filter(s -> s.name().equals(stageName))
                .findFirst().orElse(null);
        if (stage == null) {
            throw new IllegalArgumentException("未知阶段: " + stageName);
        }

        java.util.Map<String, Object> diag = new java.util.LinkedHashMap<>();
        diag.put("stage", stageName);
        diag.put("date", dateStr);

        // 1. 今天该阶段的 task 数(从 pipeline_stage.seeded_count)
        PipelineRun run = pipelineMapper.selectRunByDate(date);
        int todaySeeded = 0;
        if (run != null) {
            PipelineStageRecord rec = pipelineMapper.selectStage(run.getRunId(), stageName);
            todaySeeded = rec != null && rec.getSeededCount() != null ? rec.getSeededCount() : 0;
        }
        diag.put("todaySeeded", todaySeeded);

        // 2. 历史平均 seeded_count(近 30 天)
        java.util.Map<String, Object> avgResult = pipelineMapper.selectAvgSeeded(stageName, date.minusDays(30));
        int avgSeeded = 0;
        int sampleCount = 0;
        if (avgResult != null) {
            avgSeeded = avgResult.get("avg_seeded") != null ? ((Number) avgResult.get("avg_seeded")).intValue() : 0;
            sampleCount = avgResult.get("sampleCount") != null ? ((Number) avgResult.get("sampleCount")).intValue() : 0;
        }
        diag.put("historyAvgSeeded", avgSeeded);
        diag.put("historySampleCount", sampleCount);

        // 3. 判断 admin 是否下发完整(今天的 >= 历史平均 × 0.8,或无历史时 > 0 即可)
        boolean seedComplete;
        if (sampleCount == 0) {
            seedComplete = todaySeeded > 0;  // 无历史:只要有 task 就算完整
        } else {
            double threshold = Math.max(1, avgSeeded * 0.8);
            seedComplete = todaySeeded >= threshold;
        }
        diag.put("seedComplete", seedComplete);

        // 4. 如果有 task,查任务状态分布(PENDING/CLAIMED/SUCCESS/FAILED/DEAD)
        int failedTasks = 0;
        int deadTasks = 0;
        int successTasks = 0;
        int pendingTasks = 0;
        int claimedTasks = 0;
        int retryTasks = 0;
        java.util.Map<String, Integer> statusDist = new java.util.LinkedHashMap<>();
        java.util.List<java.util.Map<String, Object>> errorDist = new java.util.ArrayList<>();
        if (todaySeeded > 0 && run != null) {
            List<java.util.Map<String, Object>> statusList =
                    crawlTaskMapper.countByTaskTypesAndDate(stage.getTaskTypes(), dateStr);
            for (java.util.Map<String, Object> row : statusList) {
                String st = (String) row.get("status");
                int cnt = ((Number) row.get("cnt")).intValue();
                statusDist.put(st, cnt);
                if ("FAILED".equals(st)) failedTasks = cnt;
                else if ("DEAD".equals(st)) deadTasks = cnt;
                else if ("SUCCESS".equals(st)) successTasks = cnt;
                else if ("PENDING".equals(st)) pendingTasks = cnt;
                else if ("CLAIMED".equals(st)) claimedTasks = cnt;
                else if ("RETRY".equals(st)) retryTasks = cnt;
            }
            // 失败任务的错误信息 top 5,用于区分"worker 没认领"vs"代理问题"vs"数据源报错"
            if (failedTasks > 0 || deadTasks > 0) {
                errorDist = crawlTaskMapper.countErrorsByTaskTypesAndDate(stage.getTaskTypes(), dateStr, 5);
            }
        }
        diag.put("failedTasks", failedTasks);
        diag.put("deadTasks", deadTasks);
        diag.put("successTasks", successTasks);
        diag.put("pendingTasks", pendingTasks);
        diag.put("claimedTasks", claimedTasks);
        diag.put("retryTasks", retryTasks);
        diag.put("statusDistribution", statusDist);
        diag.put("errorDistribution", errorDist);

        int expected = 0;
        int actual = 0;
        int actualCurrentRun = 0;
        if (run != null) {
            PipelineStageRecord rec = pipelineMapper.selectStage(run.getRunId(), stageName);
            if (rec != null) {
                expected = rec.getExpectedTotal() == null ? 0 : rec.getExpectedTotal();
                actual = rec.getActualTotal() == null ? 0 : rec.getActualTotal();
                if (realtimeCount) {
                    actual = totalCountValidator.countActualByStage(stageName, date, defaultSource);
                }
                // 本次 run 实际产出(只算 create_date=当天,排除历史存量干扰)
                actualCurrentRun = totalCountValidator.countActualByStage(stageName, date, defaultSource, true);
            }
        }
        diag.put("expectedTotal", expected);
        diag.put("actualTotal", actual);
        diag.put("actualCurrentRun", actualCurrentRun);

        // 5. 诊断结论(按优先级细化)
        String reason;
        String action;
        if (!seedComplete) {
            reason = "SEED_INCOMPLETE";
            action = "任务未完整下发,点「修复数据」会重新探测并下发";
        } else if (pendingTasks > 0 && successTasks == 0 && failedTasks == 0 && claimedTasks == 0) {
            // 全是 PENDING → worker 根本没认领(可能 worker 没在跑)
            reason = "WORKER_NOT_CLAIMED";
            action = String.format("任务全 PENDING(%d 个),worker 未认领。请确认 worker 正在运行,确认后点「修复数据」重跑", pendingTasks);
        } else if (failedTasks > 0 || deadTasks > 0) {
            // 有失败任务,按错误信息给建议
            String topError = errorDist.isEmpty() ? "" : String.valueOf(errorDist.get(0).get("error_msg"));
            reason = "TASK_FAILED";
            if (topError != null && topError.contains("proxy")) {
                action = String.format("存在失败 task(FAILED=%d, DEAD=%d),疑似代理/IP 问题(%s),检查代理池后点「修复数据」重抓",
                        failedTasks, deadTasks, topError);
            } else if (topError != null && topError.contains("timeout")) {
                action = String.format("存在失败 task(FAILED=%d, DEAD=%d),疑似超时(%s),可直接点「修复数据」重试",
                        failedTasks, deadTasks, topError);
            } else {
                action = String.format("存在失败 task(FAILED=%d, DEAD=%d),点「修复数据」重抓。top 错误:%s",
                        failedTasks, deadTasks, topError);
            }
        } else if (expected > 0 && actualCurrentRun < expected) {
            reason = "DATA_INCOMPLETE";
            action = String.format("任务都成功但本次产出不足(实/期=%d/%d),点「修复数据」整阶段重抓", actualCurrentRun, expected);
        } else {
            reason = "ENGINE_DEDUP";
            action = "task 全部成功,行数差异疑似引擎去重或口径差异,需人工确认";
        }
        diag.put("reason", reason);
        diag.put("action", action);

        return diag;
    }

    // ---------------- 手动重试失败阶段 ----------------

    /**
     * 手动重试某次跑批中失败的阶段:重置该日期下 DEAD/FAILED 任务为 PENDING → 等完成 → 重新校验。
     *
     * @return 该阶段重试后的结果(含 userMessage)
     */
    public PipelineStageRecord retryStage(String dateStr, String stageName) {
        LocalDate date = LocalDate.parse(dateStr);
        PipelineStage stage = Stream.of(PipelineStage.values())
                .filter(s -> s.name().equals(stageName))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("未知阶段: " + stageName));

        // 1. 找该日期最新 run
        PipelineRun run = pipelineMapper.selectRunByDate(date);
        if (run == null) {
            throw new IllegalStateException("该日期无跑批记录: " + dateStr);
        }

        // 2. 重新下发(池子会作废旧分页任务)并把该阶段任务重置为 PENDING
        SeedResult seed = replayStageTasks(stage, dateStr);
        updateStageSeedInfo(run, stageName, seed);
        log.info("[pipeline] retryStage date={} stage={} seedInserted={} expected={}",
                dateStr, stageName, seed.inserted(), seed.expectedTotal());

        // 3. 重置该阶段行为 RUNNING(保留 expected,校验要用)
        pgJdbc.update(
                "UPDATE pipeline_stage SET status='RUNNING', " +
                "actual_total=NULL, dup_rows=NULL, lost_rows=NULL, duration_ms=NULL, " +
                "check_result=NULL, error_msg=NULL, started_at=now(), finished_at=NULL " +
                "WHERE run_id=? AND stage_name=?",
                run.getRunId(), stageName);

        // 4. 等待完成 + 重新校验(复用 executeStage 的核心逻辑)
        PipelineStageResult result = new PipelineStageResult();
        result.stage = stageName;
        long t0 = System.currentTimeMillis();
        try {
            int expectedTotal = seed.expectedTotal() > 0 ? seed.expectedTotal()
                    : (pipelineMapper.selectStage(run.getRunId(), stageName) != null
                    && pipelineMapper.selectStage(run.getRunId(), stageName).getExpectedTotal() != null
                    ? pipelineMapper.selectStage(run.getRunId(), stageName).getExpectedTotal() : 0);

            // 4.2 等待完成
            await(date, stage);

            // 4.3 校验
            int baselineRows = totalCountValidator.baselineRows(stage, date, defaultSource);
            ValidateContext ctx = ValidateContext.of(expectedTotal, defaultSource, List.of(), baselineRows);
            List<ValidateResult> checkResults = new ArrayList<>();
            for (PipelineValidator v : validators) {
                checkResults.add(v.validate(date, stage, ctx));
            }
            result.checkResults = checkResults;
            boolean allPassed = checkResults.stream().allMatch(ValidateResult::passed);
            result.status = allPassed ? "DONE" : "FAILED";
            result.seededCount = seed.inserted();
            result.expectedTotal = expectedTotal;
            result.actualTotal = actualTotal(checkResults);
            result.dupRows = dupRows(checkResults);
            result.lostRows = lostRows(checkResults);

            if (!allPassed) {
                writeAlert(date, stage, result, checkResults);
            }
            persistStage(run.getRunId(), stageName, result);
        } catch (Exception e) {
            log.error("[pipeline] retryStage date={} stage={} 异常: {}", dateStr, stageName, e.getMessage(), e);
            result.status = "FAILED";
            result.errorMsg = e.getMessage();
            persistStage(run.getRunId(), stageName, result);
        }
        result.durationMs = System.currentTimeMillis() - t0;

        // 5. 返回更新后的 stage 记录(带 userMessage)
        PipelineStageRecord updated = pipelineMapper.selectStage(run.getRunId(), stageName);
        if (updated != null) {
            updated.setUserMessage(summarize(updated));
        }
        return updated;
    }

    /** 手动确认某失败阶段通过(引擎去重/口径差异时人工确认)。 */
    public PipelineStageRecord confirmStage(String dateStr, String stageName) {
        LocalDate date = LocalDate.parse(dateStr);
        PipelineRun run = pipelineMapper.selectRunByDate(date);
        if (run == null) {
            throw new IllegalStateException("该日期无跑批记录: " + dateStr);
        }
        pgJdbc.update(
                "UPDATE pipeline_stage SET status='DONE', finished_at=now(), " +
                "error_msg='manual confirm: engine dedup or caliber diff' " +
                "WHERE run_id=? AND stage_name=?",
                run.getRunId(), stageName);
        PipelineStageRecord updated = pipelineMapper.selectStage(run.getRunId(), stageName);
        if (updated != null) {
            updated.setUserMessage("已手动确认通过(引擎去重/口径差异)");
        }
        return updated;
    }

    // ---------------- 单阶段修复(万能按钮) ----------------

    /** 重新下发某阶段的种子任务(幂等:insertIfAbsent 跳过已存在的任务)。 */
    public SeedResult reseedStage(String dateStr, String stageName) {
        PipelineStage stage = resolveStage(stageName);
        SeedResult result = stageSeeder.seed(stage, LocalDate.parse(dateStr), defaultSource);
        // 更新阶段记录的 seeded_count / expected_total
        PipelineRun run = pipelineMapper.selectRunByDate(LocalDate.parse(dateStr));
        if (run != null) {
            pgJdbc.update("UPDATE pipeline_stage SET seeded_count=?, expected_total=? WHERE run_id=? AND stage_name=?",
                    result.inserted(), result.expectedTotal(), run.getRunId(), stageName);
        }
        log.info("[fixStage] reseed date={} stage={} inserted={} expectedTotal={}",
                dateStr, stageName, result.inserted(), result.expectedTotal());
        return result;
    }

    /**
     * 自动修复失败阶段(全自动运营入口:点修复按钮 → 自动诊断 → 自动修复 → 返回结果)。
     * <p>根据诊断结论分策略执行,无需人工判断 SQL:</p>
     * <ul>
     *   <li>SEED_INCOMPLETE/WORKER_NOT_CLAIMED → 重新下发 + 提示检查 worker</li>
     *   <li>TASK_FAILED → 重置失败任务 + 整阶段重跑</li>
     *   <li>DATA_INCOMPLETE → 整阶段重抓</li>
     *   <li>ENGINE_DEDUP → 提示人工确认(不自动修复)</li>
     * </ul>
     */
    public Map<String, Object> fixStage(String dateStr, String stageName) {
        PipelineStage stage = resolveStage(stageName);
        LocalDate date = LocalDate.parse(dateStr);
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("stage", stageName);
        r.put("date", dateStr);

        PipelineRun run = pipelineMapper.selectRunByDate(date);
        if (run == null) {
            throw new IllegalStateException("该日期无跑批记录: " + dateStr);
        }

        // 1. 自动诊断:获取任务状态分布、失败错误、本次 run 产出
        Map<String, Object> diag = diagnoseStage(dateStr, stageName);
        String reason = (String) diag.get("reason");
        int expected = (int) diag.getOrDefault("expectedTotal", 0);
        int actualCurrentRun = (int) diag.getOrDefault("actualCurrentRun", 0);
        @SuppressWarnings("unchecked")
        Map<String, Integer> statusDist = (Map<String, Integer>) diag.getOrDefault("statusDistribution", Map.of());
        int pendingTasks = statusDist.getOrDefault("PENDING", 0);
        int successTasks = statusDist.getOrDefault("SUCCESS", 0);
        int failedTasks = statusDist.getOrDefault("FAILED", 0) + statusDist.getOrDefault("DEAD", 0);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> errorDist = (List<Map<String, Object>>) diag.getOrDefault("errorDistribution", List.of());
        String topError = errorDist.isEmpty() ? "" : String.valueOf(errorDist.get(0).get("error_msg"));

        log.info("[fixStage] date={} stage={} reason={} expected={} actualCurrentRun={} statusDist={} topError={}",
                dateStr, stageName, reason, expected, actualCurrentRun, statusDist, topError);

        // 2. 按诊断结论分策略执行
        int actualBefore = totalCountValidator.countActualByStage(stageName, date, defaultSource);
        r.put("diagnosis", reason);
        r.put("actualBefore", actualBefore);
        r.put("actualCurrentRunBefore", actualCurrentRun);

        // 策略 A:worker 根本没认领(全 PENDING,无 SUCCESS/FAILED) → 重新下发 + 提示检查 worker,不阻塞等 worker
        if ("WORKER_NOT_CLAIMED".equals(reason)) {
            SeedResult seed = replayStageTasks(stage, dateStr);
            updateStageSeedInfo(run, stageName, seed);
            markStageRunning(run, stageName);
            r.put("action", "RESEED_NEED_WORKER");
            r.put("seedInserted", seed.inserted());
            r.put("status", "PENDING");
            r.put("message", String.format(
                    "任务全 PENDING(%d 个),worker 未认领。已重新下发(%d 个任务),请确认 worker 正在运行,完成后可再点修复重校验。",
                    pendingTasks, seed.inserted()));
            return r;
        }

        // 策略 B:任务失败(FAILED/DEAD) → 重置失败任务 + 整阶段重跑
        if ("TASK_FAILED".equals(reason)) {
            SeedResult seed = replayStageTasks(stage, dateStr);
            updateStageSeedInfo(run, stageName, seed);
            markStageRunning(run, stageName);
            // 等待完成 + 重新校验
            return executeFixAndValidate(date, run, stage, seed, r, String.format(
                    "已重置失败任务(FAILED+DEAD=%d)并重新下发,top 错误:%s。等待 worker 完成中...", failedTasks, topError));
        }

        // 策略 C:数据不足(任务都成功但产出 < 期望) → 整阶段重抓
        if ("DATA_INCOMPLETE".equals(reason)) {
            SeedResult seed = replayStageTasks(stage, dateStr);
            updateStageSeedInfo(run, stageName, seed);
            markStageRunning(run, stageName);
            return executeFixAndValidate(date, run, stage, seed, r, String.format(
                    "任务都成功但本次产出不足(实/期=%d/%d),已整阶段重抓,等待 worker 完成中...", actualCurrentRun, expected));
        }

        // 策略 D:引擎去重/口径差异 → 不自动修复,提示人工确认
        if ("ENGINE_DEDUP".equals(reason)) {
            r.put("action", "NEED_MANUAL_CONFIRM");
            r.put("status", "FAILED");
            r.put("message", String.format(
                    "task 全部成功,行数差异(实/期=%d/%d)疑似引擎去重或口径差异,不自动修复。请确认数据可接受后点「确认通过」。",
                    actualCurrentRun, expected));
            return r;
        }

        // 策略 E:兜底(默认走整阶段重抓)
        SeedResult seed = replayStageTasks(stage, dateStr);
        updateStageSeedInfo(run, stageName, seed);
        markStageRunning(run, stageName);
        return executeFixAndValidate(date, run, stage, seed, r, "已整阶段重抓,等待 worker 完成中...");
    }

    /** fixStage 的公共后续:等待 worker 完成 + 重新校验。 */
    private Map<String, Object> executeFixAndValidate(LocalDate date, PipelineRun run, PipelineStage stage,
                                                       SeedResult seed, Map<String, Object> r, String actionMsg) {
        PipelineStageResult result = new PipelineStageResult();
        result.stage = stage.name();
        long t0 = System.currentTimeMillis();
        try {
            int expectedTotal = seed.expectedTotal() > 0 ? seed.expectedTotal() : 0;
            if (!stage.getTaskTypes().isEmpty()) {
                await(date, stage);
            }
            ValidateContext ctx = ValidateContext.of(expectedTotal, defaultSource, seed.taskIds());
            List<ValidateResult> checkResults = new ArrayList<>();
            for (PipelineValidator v : validators) {
                checkResults.add(v.validate(date, stage, ctx));
            }
            result.checkResults = checkResults;
            boolean allPassed = checkResults.stream().allMatch(ValidateResult::passed);
            result.status = allPassed ? "DONE" : "FAILED";
            result.seededCount = seed.inserted();
            result.expectedTotal = expectedTotal;
            result.actualTotal = actualTotal(checkResults);
            result.dupRows = dupRows(checkResults);
            result.lostRows = lostRows(checkResults);
            if (!allPassed) {
                writeAlert(date, stage, result, checkResults);
            }
            persistStage(run.getRunId(), stage.name(), result);
        } catch (Exception e) {
            log.error("[fixStage] date={} stage={} 异常: {}", date, stage.name(), e.getMessage(), e);
            result.status = "FAILED";
            result.errorMsg = e.getMessage();
            persistStage(run.getRunId(), stage.name(), result);
        }
        result.durationMs = System.currentTimeMillis() - t0;

        r.put("action", "REPLAY");
        r.put("seedInserted", seed.inserted());
        r.put("expectedTotal", result.expectedTotal);
        r.put("actualAfter", result.actualTotal);
        r.put("status", result.status);
        r.put("message", "DONE".equals(result.status)
                ? actionMsg + String.format(" 修复完成!实际 %d 行(期望 %d)", result.actualTotal, result.expectedTotal)
                : actionMsg + String.format(" 仍未对齐。实际 %d 行(期望 %d),可再点一次或查看 worker 日志", result.actualTotal, result.expectedTotal));
        log.info("[fixStage] date={} stage={} action=REPLAY status={} actualAfter={}", date, stage.name(), result.status, result.actualTotal);
        return r;
    }

    // ---------------- 独立阶段定时调度(18:00 / 18:30) ----------------

    /**
     * 独立运行龙虎榜主表阶段(DRAGON_TIGER)：seed → 等待完成 → 校验。
     * <p>供 18:00 定时调度调用(此时东财已发布龙虎榜数据)。
     * 复用现有 run 记录(不新建),仅执行 DRAGON_TIGER 单个阶段。</p>
     *
     * @param dateStr 交易日(yyyy-MM-dd)，缺省当天
     * @return 阶段执行结果
     */
    public PipelineStageResult runDragonTigerStage(String dateStr) {
        LocalDate date = LocalDate.parse(dateStr);
        PipelineStage stage = PipelineStage.DRAGON_TIGER;
        log.info("[pipeline-18:00] ===== 龙虎榜主表独立调度启动 date={} =====", dateStr);

        // 1. 确保有 run 记录(复用今天的,不新建)
        PipelineRun run = pipelineMapper.selectRunByDate(date);
        if (run == null) {
            Long runId = ensureRun(date, true);
            if (runId == null) {
                run = pipelineMapper.selectRunByDate(date);
            } else {
                run = pipelineMapper.selectRunById(runId);
            }
        }
        if (run == null) {
            PipelineStageResult empty = new PipelineStageResult();
            empty.stage = stage.name();
            empty.status = "FAILED";
            empty.errorMsg = "无法创建或获取 run 记录";
            return empty;
        }

        // 2. 执行该阶段(seed + await + validate)
        PipelineStageResult result = executeStage(date, run.getRunId(), stage);
        log.info("[pipeline-18:00] ===== 龙虎榜主表独立调度完成 date={} status={} actual={}/{} =====",
                dateStr, result.status, result.actualTotal, result.expectedTotal);
        return result;
    }

    /**
     * 独立运行龙虎榜明细阶段(DRAGON_TIGER_DETAIL)：从 dragon_tiger 表读 TRADE_ID → 下发子任务 → 等待完成 → 校验。
     * <p>供 18:30 定时调度调用(需在 DRAGON_TIGER 爬完并落库 dragon_tiger 表后执行)。</p>
     *
     * @param dateStr 交易日(yyyy-MM-dd)，缺省当天
     * @return 阶段执行结果
     */
    public PipelineStageResult runDragonTigerDetailStage(String dateStr) {
        LocalDate date = LocalDate.parse(dateStr);
        PipelineStage stage = PipelineStage.DRAGON_TIGER_DETAIL;
        log.info("[pipeline-18:30] ===== 龙虎榜明细独立调度启动 date={} =====", dateStr);

        // 1. 确保有 run 记录(复用今天的)
        PipelineRun run = pipelineMapper.selectRunByDate(date);
        if (run == null) {
            Long runId = ensureRun(date, true);
            if (runId == null) {
                run = pipelineMapper.selectRunByDate(date);
            } else {
                run = pipelineMapper.selectRunById(runId);
            }
        }
        if (run == null) {
            PipelineStageResult empty = new PipelineStageResult();
            empty.stage = stage.name();
            empty.status = "FAILED";
            empty.errorMsg = "无法创建或获取 run 记录";
            return empty;
        }

        // 2. 执行链式阶段(seed + await + validate)
        PipelineStageResult result = executeChainStage(date, run.getRunId(), stage);
        log.info("[pipeline-18:30] ===== 龙虎榜明细独立调度完成 date={} status={} actual={}/{} =====",
                dateStr, result.status, result.actualTotal, result.expectedTotal);
        return result;
    }

    /** 兼容旧入口:retryStage 复用 fixStage 的自动诊断 + 修复逻辑。 */

    private PipelineStage resolveStage(String stageName) {
        return Stream.of(PipelineStage.values())
                .filter(s -> s.name().equals(stageName))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("未知阶段: " + stageName));
    }

    private static final Set<String> POOL_STAGE_NAMES = Set.of(
            "LIMIT_UP", "LIMIT_DOWN", "LIMIT_ZHABAN", "STRONG_POOL", "CIXIN_POOL");

    /**
     * 重新下发并重置该阶段任务,使 worker 再抓一次。
     * 池子:作废 unique_key 带页码后缀的旧任务,只把全日任务打回 PENDING。
     */
    private SeedResult replayStageTasks(PipelineStage stage, String dateStr) {
        SeedResult seed = stageSeeder.seed(stage, LocalDate.parse(dateStr), defaultSource);
        List<String> taskTypes = stage.getTaskTypes();
        if (taskTypes.isEmpty()) {
            return seed;
        }
        if (POOL_STAGE_NAMES.contains(stage.name())) {
            for (String t : taskTypes) {
                String key = t + "|" + defaultSource + "|" + dateStr;
                crawlTaskMapper.resetTaskByUniqueKey(key);
            }
        } else {
            crawlTaskMapper.resetTasksByTaskTypesAndDate(
                    taskTypes, dateStr, List.of("SUCCESS", "FAILED", "DEAD", "CLAIMED", "RETRY"));
        }
        return seed;
    }

    private void updateStageSeedInfo(PipelineRun run, String stageName, SeedResult seed) {
        if (run == null) return;
        pgJdbc.update("UPDATE pipeline_stage SET seeded_count=?, expected_total=? WHERE run_id=? AND stage_name=?",
                seed.inserted(), seed.expectedTotal(), run.getRunId(), stageName);
    }

    private void markStageRunning(PipelineRun run, String stageName) {
        if (run == null) return;
        pgJdbc.update("UPDATE pipeline_stage SET status='RUNNING', error_msg=NULL WHERE run_id=? AND stage_name=?",
                run.getRunId(), stageName);
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
