package com.dunwugudao.crawler.admin.job;

import com.dunwugudao.crawler.admin.schedule.RetryScanService;
import com.dunwugudao.crawler.admin.seed.SeedGenerator;
import com.dunwugudao.crawler.admin.seed.TaskTypeCatalog;
import com.xxl.job.core.context.XxlJobHelper;
import com.xxl.job.core.handler.annotation.XxlJob;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * XXL-JOB 任务处理器（M3-4）。核心逻辑在 {@link SeedGenerator} / {@link RetryScanService}，
 * 此处仅做参数解析与 {@link XxlJobHelper#log(String)} 记录。不发版 xxl-job-admin 时也能跑——见 {@link JobController}。
 */
@Slf4j
@Component
public class XxlJobHandlers {

    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    private final SeedGenerator seedGenerator;
    private final RetryScanService retryScanService;

    public XxlJobHandlers(SeedGenerator seedGenerator, RetryScanService retryScanService) {
        this.seedGenerator = seedGenerator;
        this.retryScanService = retryScanService;
    }

    /** 收盘后每日种子。param: date=2024-01-02&source=1（缺失则 date=今天、source=1）。 */
    @XxlJob("dailyCloseSeed")
    public void dailyCloseSeed() {
        Map<String, String> p = parse(XxlJobHelper.getJobParam());
        String date = p.getOrDefault("date", LocalDate.now().format(FMT));
        int source = parseInt(p.get("source"), 1);
        int n = seedGenerator.dailySeed(date, source);
        XxlJobHelper.log("dailyCloseSeed date={} source={} inserted={}", date, source, n);
    }

    /** 历史区间回填。param: start=&end=&source=&types=（types 逗号分隔；缺失则全部）。 */
    @XxlJob("historyBackfill")
    public void historyBackfill() {
        Map<String, String> p = parse(XxlJobHelper.getJobParam());
        String start = p.get("start");
        String end = p.get("end");
        if (start == null || end == null) {
            XxlJobHelper.log("historyBackfill 缺少 start/end，跳过");
            return;
        }
        int source = parseInt(p.get("source"), 1);
        List<String> types = parseTypes(p.get("types"));
        int n = seedGenerator.backfill(start, end, source, types);
        XxlJobHelper.log("historyBackfill start={} end={} source={} types={} inserted={}",
                start, end, source, types, n);
    }

    /** 僵尸回收 + 耗尽重试置 DEAD。param: timeoutMin=（默认 60）。 */
    @XxlJob("retryScan")
    public void retryScan() {
        Map<String, String> p = parse(XxlJobHelper.getJobParam());
        int timeoutMin = parseInt(p.get("timeoutMin"), 60);
        int reclaimed = retryScanService.reclaimZombies(timeoutMin);
        int promoted = retryScanService.promoteExhausted();
        XxlJobHelper.log("retryScan reclaimed={} promoted={}", reclaimed, promoted);
    }

    /**
     * 串联龙虎榜明细：从 dragon_tiger 表读某交易日上榜代码 → 下发 DRAGON_TIGER_DETAIL 子任务。
     * <p>需在 DRAGON_TIGER 爬完后调用。param: date=2024-01-02（缺失则今天）。</p>
     */
    @XxlJob("chainDragonDetails")
    public void chainDragonDetails() {
        Map<String, String> p = parse(XxlJobHelper.getJobParam());
        String date = p.getOrDefault("date", LocalDate.now().format(FMT));
        int n = seedGenerator.chainDragonTigerDetails(date);
        XxlJobHelper.log("chainDragonDetails date={} inserted={}", date, n);
    }

    /** 解析 XXL-JOB 的 k1=v1&k2=v2 形式 param。 */
    private Map<String, String> parse(String param) {
        Map<String, String> m = new LinkedHashMap<>();
        if (param == null || param.isBlank()) {
            return m;
        }
        for (String kv : param.split("&")) {
            int idx = kv.indexOf('=');
            if (idx < 0) {
                continue;
            }
            m.put(kv.substring(0, idx).trim(), kv.substring(idx + 1).trim());
        }
        return m;
    }

    private int parseInt(String s, int def) {
        if (s == null || s.isBlank()) {
            return def;
        }
        try {
            return Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            return def;
        }
    }

    private List<String> parseTypes(String s) {
        if (s == null || s.isBlank()) {
            return new ArrayList<>();
        }
        return Arrays.stream(s.split(","))
                .map(String::trim)
                .filter(t -> !t.isEmpty())
                .toList();
    }

    // 校验：确保引用到的常量编译期存在（避免误用未来类型）
    static {
        TaskTypeCatalog.ALL.size();
    }
}
