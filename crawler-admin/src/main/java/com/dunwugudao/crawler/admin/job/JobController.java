package com.dunwugudao.crawler.admin.job;

import com.dunwugudao.crawler.admin.schedule.RetryScanService;
import com.dunwugudao.crawler.admin.seed.SeedGenerator;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 手动触发任务（M3-4）。无需部署 xxl-job-admin 也能驱动 M3 三件套，便于 M6 测试与运维临时补数据。
 */
@RestController
@RequestMapping("/api/job")
public class JobController {

    private final SeedGenerator seedGenerator;
    private final RetryScanService retryScanService;

    public JobController(SeedGenerator seedGenerator, RetryScanService retryScanService) {
        this.seedGenerator = seedGenerator;
        this.retryScanService = retryScanService;
    }

    @PostMapping("/daily-seed")
    public Map<String, Object> dailySeed(
            @RequestParam(required = false) @DateTimeFormat(pattern = "yyyy-MM-dd") LocalDate date,
            @RequestParam(required = false, defaultValue = "1") int source) {
        String d = (date == null ? LocalDate.now() : date).format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd"));
        int inserted = seedGenerator.dailySeed(d, source);
        Map<String, Object> r = new HashMap<>();
        r.put("inserted", inserted);
        return r;
    }

    /** 单独下发 STOCK_DAILY 任务(测试用) */
    @PostMapping("/seed-stock-daily")
    public Map<String, Object> seedStockDaily(
            @RequestParam(required = false) @DateTimeFormat(pattern = "yyyy-MM-dd") LocalDate date,
            @RequestParam(required = false, defaultValue = "1") int source) {
        String d = (date == null ? LocalDate.now() : date).format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd"));
        int inserted = seedGenerator.seedStockDailyPages(source, d);
        Map<String, Object> r = new HashMap<>();
        r.put("inserted", inserted);
        return r;
    }

    /** 仅下发 5 个池子任务（涨停/跌停/炸板/强势/次新），端到端测试用 */
    @PostMapping("/seed-pools-only")
    public Map<String, Object> seedPoolsOnly(
            @RequestParam(required = false) @DateTimeFormat(pattern = "yyyy-MM-dd") LocalDate date,
            @RequestParam(required = false, defaultValue = "1") int source) {
        String d = (date == null ? LocalDate.now() : date).format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd"));
        int inserted = seedGenerator.dailySeedPoolsOnly(d, source);
        Map<String, Object> r = new HashMap<>();
        r.put("inserted", inserted);
        return r;
    }

    /** 仅下发 3 种 board_basic 任务（地域/行业/概念板块基础维表），端到端测试用 */
    @PostMapping("/seed-board-basic")
    public Map<String, Object> seedBoardBasic(
            @RequestParam(required = false) @DateTimeFormat(pattern = "yyyy-MM-dd") LocalDate date,
            @RequestParam(required = false, defaultValue = "1") int source) {
        String d = (date == null ? LocalDate.now() : date).format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd"));
        int inserted = seedGenerator.seedBoardBasicAll(source, d);
        Map<String, Object> r = new HashMap<>();
        r.put("inserted", inserted);
        return r;
    }

    /** 仅下发 3 种 board_daily 任务（地域/行业/概念），端到端测试用 */
    @PostMapping("/seed-board-daily")
    public Map<String, Object> seedBoardDaily(
            @RequestParam(required = false) @DateTimeFormat(pattern = "yyyy-MM-dd") LocalDate date,
            @RequestParam(required = false, defaultValue = "1") int source) {
        String d = (date == null ? LocalDate.now() : date).format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd"));
        int inserted = seedGenerator.seedBoardDailyAll(source, d);
        Map<String, Object> r = new HashMap<>();
        r.put("inserted", inserted);
        return r;
    }

    /** 只跑单个板块的 STOCK_BY_BOARD（端到端测试用），boardCode 必传 */
    @PostMapping("/seed-stock-by-board")
    public Map<String, Object> seedStockByBoard(
            @RequestParam String boardCode,
            @RequestParam(required = false) @DateTimeFormat(pattern = "yyyy-MM-dd") LocalDate date,
            @RequestParam(required = false, defaultValue = "1") int source) {
        String d = (date == null ? LocalDate.now() : date).format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd"));
        int inserted = seedGenerator.seedSingleBoard(boardCode, source, d);
        Map<String, Object> r = new HashMap<>();
        r.put("boardCode", boardCode);
        r.put("inserted", inserted);
        return r;
    }

    /** 批量跑所有板块的 STOCK_BY_BOARD（读 board_basic 表，逐板块探测 total → 按页拆任务） */
    @PostMapping("/seed-stock-by-board-all")
    public Map<String, Object> seedStockByBoardAll(
            @RequestParam(required = false) @DateTimeFormat(pattern = "yyyy-MM-dd") LocalDate date,
            @RequestParam(required = false, defaultValue = "1") int source) {
        String d = (date == null ? LocalDate.now() : date).format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd"));
        int inserted = seedGenerator.seedByBoard(source, d);
        Map<String, Object> r = new HashMap<>();
        r.put("inserted", inserted);
        return r;
    }

    @PostMapping("/backfill")
    public Map<String, Object> backfill(
            @RequestParam String start,
            @RequestParam String end,
            @RequestParam(required = false, defaultValue = "1") int source,
            @RequestParam(required = false) String types) {
        List<String> typeList = (types == null || types.isBlank())
                ? new java.util.ArrayList<>()
                : Arrays.stream(types.split(",")).map(String::trim).filter(t -> !t.isEmpty()).toList();
        int inserted = seedGenerator.backfill(start, end, source, typeList);
        Map<String, Object> r = new HashMap<>();
        r.put("inserted", inserted);
        return r;
    }

    @PostMapping("/retry-scan")
    public Map<String, Object> retryScan(
            @RequestParam(required = false, defaultValue = "15") int timeoutMin) {
        int reclaimed = retryScanService.reclaimZombies(timeoutMin);
        int promoted = retryScanService.promoteExhausted();
        Map<String, Object> r = new HashMap<>();
        r.put("reclaimed", reclaimed);
        r.put("promoted", promoted);
        return r;
    }

    /**
     * 增量同步板块-个股关联（stock_board_rel）。
     * <p>两步校验：板块数（board_basic vs stock_board_rel）+ 股票数（API total vs 库）。
     * 无变化返回 inserted=0；有新增板块只探测新增。worker 幂等，重复跑不重复数据。</p>
     */
    @PostMapping("/sync-board-relations")
    public Map<String, Object> syncBoardRelations(
            @RequestParam(required = false) @DateTimeFormat(pattern = "yyyy-MM-dd") LocalDate date,
            @RequestParam(required = false, defaultValue = "1") int source) {
        String d = (date == null ? LocalDate.now() : date).format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd"));
        int inserted = seedGenerator.syncBoardRelations(source, d);
        Map<String, Object> r = new HashMap<>();
        r.put("inserted", inserted);
        return r;
    }

    /**
     * 串联龙虎榜明细：从 dragon_tiger 表读某交易日上榜代码 → 下发 DRAGON_TIGER_DETAIL 子任务。
     * <p>需在 DRAGON_TIGER 爬完后调用。param: date=2024-01-02（缺失则今天）。</p>
     */
    @PostMapping("/chain-dragon-details")
    public Map<String, Object> chainDragonDetails(
            @RequestParam(required = false) @DateTimeFormat(pattern = "yyyy-MM-dd") LocalDate date) {
        String d = (date == null ? LocalDate.now() : date).format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd"));
        int inserted = seedGenerator.chainDragonTigerDetails(d);
        Map<String, Object> r = new HashMap<>();
        r.put("inserted", inserted);
        return r;
    }
}
