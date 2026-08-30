package com.dunwugudao.crawler.admin.job;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.Map;

/**
 * Job 概览 API (M6-前端全景页)。
 * <p>GET /api/job/overview?date=2026-08-25 → 当日全部 job 状态 + worker 列表。</p>
 */
@RestController
@RequestMapping("/api/job/overview")
public class JobOverviewController {

    private final JobOverviewService jobOverviewService;

    public JobOverviewController(JobOverviewService jobOverviewService) {
        this.jobOverviewService = jobOverviewService;
    }

    /** 今日概览 */
    @GetMapping("/today")
    public Map<String, Object> today() {
        return jobOverviewService.getTodayOverview();
    }

    /** 指定日期概览 */
    @GetMapping
    public Map<String, Object> byDate(
            @RequestParam(required = false) @DateTimeFormat(pattern = "yyyy-MM-dd") LocalDate date) {
        return jobOverviewService.getDateOverview(date != null ? date : LocalDate.now());
    }

    /** 单个 job 近 30 天执行历史 */
    @GetMapping("/history/{jobType}")
    public Map<String, Object> history(@PathVariable String jobType) {
        return jobOverviewService.getJobHistory(jobType);
    }

    /** 控制: 手动启动 job */
    @PostMapping("/{jobType}/start")
    public Map<String, Object> startJob(@PathVariable String jobType) {
        return jobOverviewService.startJob(jobType);
    }

    /** 控制: 手动停止 job */
    @PostMapping("/{jobType}/stop")
    public Map<String, Object> stopJob(@PathVariable String jobType) {
        return jobOverviewService.stopJob(jobType);
    }
}

/**
 * Job 配置 API。
 * <p>GET/PUT /api/job/config/{jobType} → 查询/更新 Job 调度配置。</p>
 */
@RestController
@RequestMapping("/api/job/config")
class JobConfigController {

    private final JobOverviewService jobOverviewService;

    public JobConfigController(JobOverviewService jobOverviewService) {
        this.jobOverviewService = jobOverviewService;
    }

    /** 获取 Job 配置 */
    @GetMapping("/{jobType}")
    public Map<String, Object> getConfig(@PathVariable String jobType) {
        return jobOverviewService.getJobConfig(jobType);
    }

    /** 更新 Job 配置 */
    @PutMapping("/{jobType}")
    public Map<String, Object> updateConfig(@PathVariable String jobType, @RequestBody Map<String, Object> config) {
        return jobOverviewService.updateJobConfig(jobType, config);
    }
}
