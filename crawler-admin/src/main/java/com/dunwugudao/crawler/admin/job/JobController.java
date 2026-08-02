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
