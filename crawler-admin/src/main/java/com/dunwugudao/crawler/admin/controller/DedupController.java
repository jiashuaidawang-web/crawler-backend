package com.dunwugudao.crawler.admin.controller;

import com.dunwugudao.crawler.persistence.service.DedupService;
import com.dunwugudao.crawler.persistence.service.DedupService.DupStats;
import com.dunwugudao.crawler.persistence.service.DedupService.RebuildResult;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 去重治理端点（Admin 端口 8081）。
 *
 * <p>职责：存量去重 + 防未来重复（DB 引擎层 + 代码层）。
 * 所有写操作都是幂等的（重复调用不会重复清除）。</p>
 */
@RestController
@RequestMapping("/api/dedup")
@RequiredArgsConstructor
public class DedupController {

    private final DedupService dedupService;

    /** 注册表概览：每张表的自然键/版本列/是否已是 Replacing。 */
    @GetMapping("/registry")
    public Map<String, Object> registry() {
        return Map.of("tables", DedupService.registry());
    }

    /** 单表去重统计。 */
    @GetMapping("/stats")
    public DupStats stats(@RequestParam String table) {
        return dedupService.stats(table);
    }

    /** 全表去重统计。 */
    @GetMapping("/stats-all")
    public Map<String, Object> statsAll() {
        List<DupStats> all = dedupService.statsAll();
        long totalDupGroups = all.stream().mapToLong(DupStats::getDupGroups).sum();
        long totalRemovable = all.stream().mapToLong(DupStats::getRemovableRows).sum();
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("tables", all);
        r.put("summary", Map.of(
                "totalTables", all.size(),
                "tablesWithDup", all.stream().filter(s -> s.getDupGroups() > 0).count(),
                "totalDupGroups", totalDupGroups,
                "totalRemovableRows", totalRemovable));
        return r;
    }

    /**
     * 单表 engine-fix：MergeTree → ReplacingMergeTree(_ver)。
     * <p>实际走 rebuild（建 _new → 去重迁移 → RENAME 切换 → DROP 旧表）。</p>
     */
    @PostMapping("/engine-fix")
    public RebuildResult engineFix(@RequestParam String table) {
        return dedupService.engineFix(table);
    }

    /** 全表 engine-fix。 */
    @PostMapping("/engine-fix-all")
    public List<RebuildResult> engineFixAll() {
        List<RebuildResult> results = new java.util.ArrayList<>();
        for (String table : DedupService.registry().keySet()) {
            try {
                results.add(dedupService.engineFix(table));
            } catch (Exception e) {
                RebuildResult r = new RebuildResult();
                r.setTable(table);
                r.setMessage("失败: " + e.getMessage());
                results.add(r);
            }
        }
        return results;
    }

    /**
     * 单表 rebuild（去重 + 改引擎）。
     *
     * @param dryRun true=只统计不执行
     */
    @PostMapping("/rebuild")
    public RebuildResult rebuild(@RequestParam String table,
                                 @RequestParam(defaultValue = "true") boolean dryRun) {
        return dedupService.rebuild(table, dryRun);
    }

    /** 全表 rebuild。dryRun=true 时只统计。 */
    @PostMapping("/rebuild-all")
    public List<RebuildResult> rebuildAll(@RequestParam(defaultValue = "true") boolean dryRun) {
        List<RebuildResult> results = new java.util.ArrayList<>();
        for (String table : DedupService.registry().keySet()) {
            try {
                results.add(dedupService.rebuild(table, dryRun));
            } catch (Exception e) {
                RebuildResult r = new RebuildResult();
                r.setTable(table);
                r.setMessage("失败: " + e.getMessage());
                results.add(r);
            }
        }
        return results;
    }

    /**
     * 按日期定向去重（简单模式）：同自然键只保留最新一条，删多余行。
     * <p>适用：重复集中在少数几天。CK DELETE 是异步 mutation。</p>
     *
     * @param table 表名
     * @param dates 日期，可多个，如 &dates=2026-08-07&dates=2026-08-10
     * @param dryRun true=只统计不执行
     */
    @PostMapping("/dedup-by-date")
    public DupStats dedupByDate(@RequestParam String table,
                                @RequestParam List<String> dates,
                                @RequestParam(defaultValue = "true") boolean dryRun) {
        return dedupService.dedupByDate(table, dates, dryRun);
    }
}
