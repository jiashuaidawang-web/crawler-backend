package com.dunwugudao.crawler.admin.job;

import com.dunwugudao.crawler.persistence.entity.JobDefinition;
import com.dunwugudao.crawler.persistence.entity.JobExecution;
import com.dunwugudao.crawler.persistence.entity.WorkerNode;
import com.dunwugudao.crawler.persistence.mapper.JobDefinitionMapper;
import com.dunwugudao.crawler.persistence.mapper.JobExecutionMapper;
import com.dunwugudao.crawler.persistence.mapper.WorkerNodeMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Job 概览服务: 前端「今日 Job 全景」页核心数据源。
 * <p>聚合 job_execution + worker_node + job_definition, 一览今日全部 job 状态。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class JobOverviewService {

    private final JobExecutionMapper jobExecutionMapper;
    private final JobDefinitionMapper jobDefinitionMapper;
    private final WorkerNodeMapper workerNodeMapper;
    private final JdbcTemplate pgJdbcTemplate;

    /**
     * 今日 Job 全景概览。
     *
     * @return { date, summary:{total, running, success, failed, pending}, jobs:[...], workers:[...] }
     */
    public Map<String, Object> getTodayOverview() {
        return getDateOverview(LocalDate.now());
    }

    /**
     * 指定日期 Job 全景概览。
     */
    public Map<String, Object> getDateOverview(LocalDate date) {
        // 1. 全部 job 定义
        List<JobDefinition> definitions = jobDefinitionMapper.selectAllEnabled();

        // 2. 当日执行记录
        List<JobExecution> executions = jobExecutionMapper.selectByTradeDate(date);
        Map<String, JobExecution> execByJobType = executions.stream()
                .collect(Collectors.toMap(JobExecution::getJobType, e -> e, (a, b) -> a));

        // 3. 组装 job 列表
        List<Map<String, Object>> jobs = new ArrayList<>();
        for (JobDefinition def : definitions) {
            JobExecution exec = execByJobType.get(def.getJobType());
            Map<String, Object> job = new LinkedHashMap<>();
            job.put("jobType", def.getJobType());
            job.put("displayName", def.getDisplayName());
            job.put("executorType", def.getExecutorType());
            job.put("jobCategory", def.getJobCategory());
            job.put("description", def.getDescription());
            job.put("marketDependent", def.getMarketDependent());
            job.put("scheduleStrategy", def.getScheduleStrategy());
            job.put("scheduleCron", def.getScheduleCron());
            job.put("autoStart", def.getAutoStart());
            job.put("enabled", def.getEnabled());

            if (exec != null) {
                job.put("status", exec.getStatus());
                job.put("startedAt", exec.getStartedAt());
                job.put("finishedAt", exec.getFinishedAt());
                job.put("durationMs", exec.getDurationMs());
                job.put("rowsAffected", exec.getRowsAffected());
                job.put("workerId", exec.getWorkerId());
                job.put("errorMsg", exec.getErrorMsg());
            } else {
                job.put("status", "PENDING");
                job.put("startedAt", null);
                job.put("finishedAt", null);
                job.put("durationMs", null);
                job.put("rowsAffected", null);
                job.put("workerId", null);
                job.put("errorMsg", null);
            }
            jobs.add(job);
        }

        // 4. Worker 节点
        List<WorkerNode> workers = workerNodeMapper.selectAll();

        // 5. 汇总统计
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("total", definitions.size());
        summary.put("running", executions.stream().filter(e -> "RUNNING".equals(e.getStatus())).count());
        summary.put("success", executions.stream().filter(e -> "SUCCESS".equals(e.getStatus())).count());
        summary.put("failed", executions.stream().filter(e -> "FAILED".equals(e.getStatus())).count());
        summary.put("stopped", executions.stream().filter(e -> "STOPPED".equals(e.getStatus())).count());
        summary.put("pending", definitions.size() - executions.size());
        summary.put("onlineWorkers", workers.stream().filter(w -> "ONLINE".equals(w.getStatus())).count());
        summary.put("totalWorkers", workers.size());

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("date", date.toString());
        result.put("summary", summary);
        result.put("jobs", jobs);
        result.put("workers", workers);
        return result;
    }

    /**
     * 单个 job 近 30 天执行历史。
     */
    public Map<String, Object> getJobHistory(String jobType) {
        JobDefinition def = jobDefinitionMapper.selectByJobType(jobType);
        List<JobExecution> history = jobExecutionMapper.selectRecentByJobType(jobType);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("jobType", jobType);
        result.put("displayName", def != null ? def.getDisplayName() : jobType);
        result.put("executorType", def != null ? def.getExecutorType() : "UNKNOWN");
        result.put("history", history);
        return result;
    }

    /**
     * 手动启动 job (标记为 RUNNING, 实际执行由 worker 端监听)。
     */
    public Map<String, Object> startJob(String jobType) {
        LocalDate today = LocalDate.now();
        pgJdbcTemplate.update(
                "UPDATE job_execution SET status='PENDING', updated_at=CURRENT_TIMESTAMP " +
                "WHERE job_type=? AND trade_date=? AND status IN ('STOPPED','FAILED')",
                jobType, today);
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("jobType", jobType);
        r.put("action", "START");
        r.put("message", "Job marked as PENDING, worker will pick up");
        return r;
    }

    /**
     * 获取 Job 配置。
     */
    public Map<String, Object> getJobConfig(String jobType) {
        JobDefinition def = jobDefinitionMapper.selectByJobType(jobType);
        if (def == null) {
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("error", "Job not found: " + jobType);
            return r;
        }
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("jobType", def.getJobType());
        r.put("displayName", def.getDisplayName());
        r.put("executorType", def.getExecutorType());
        r.put("jobCategory", def.getJobCategory());
        r.put("description", def.getDescription());
        r.put("scheduleStrategy", def.getScheduleStrategy());
        r.put("scheduleCron", def.getScheduleCron());
        r.put("marketDependent", def.getMarketDependent());
        r.put("autoStart", def.getAutoStart());
        r.put("enabled", def.getEnabled());
        return r;
    }

    /**
     * 更新 Job 配置。
     */
    public Map<String, Object> updateJobConfig(String jobType, Map<String, Object> config) {
        JobDefinition def = jobDefinitionMapper.selectByJobType(jobType);
        if (def == null) {
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("error", "Job not found: " + jobType);
            return r;
        }

        // 更新字段
        if (config.containsKey("displayName")) def.setDisplayName((String) config.get("displayName"));
        if (config.containsKey("description")) def.setDescription((String) config.get("description"));
        if (config.containsKey("scheduleStrategy")) def.setScheduleStrategy((String) config.get("scheduleStrategy"));
        if (config.containsKey("scheduleCron")) def.setScheduleCron((String) config.get("scheduleCron"));
        if (config.containsKey("marketDependent")) def.setMarketDependent((Boolean) config.get("marketDependent"));
        if (config.containsKey("autoStart")) def.setAutoStart((Boolean) config.get("autoStart"));
        if (config.containsKey("enabled")) def.setEnabled((Boolean) config.get("enabled"));

        jobDefinitionMapper.updateConfig(def);

        Map<String, Object> r = new LinkedHashMap<>();
        r.put("jobType", jobType);
        r.put("message", "配置已更新");
        return r;
    }

    /**
     * 手动停止 job。
     */
    public Map<String, Object> stopJob(String jobType) {
        LocalDate today = LocalDate.now();
        pgJdbcTemplate.update(
                "UPDATE job_execution SET status='STOPPED', finished_at=CURRENT_TIMESTAMP, " +
                "updated_at=CURRENT_TIMESTAMP " +
                "WHERE job_type=? AND trade_date=? AND status='RUNNING'",
                jobType, today);
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("jobType", jobType);
        r.put("action", "STOP");
        r.put("message", "Job marked as STOPPED");
        return r;
    }

    /**
     * 初始化今日 Job 执行记录 (每天 00:05 跑, 为所有 enabled 的 job 创建 PENDING 记录)。
     * openGauss 兼容: 用 WHERE NOT EXISTS 替代 ON CONFLICT DO NOTHING。
     */
    @Scheduled(cron = "0 5 0 * * *")
    public void initTodayJobExecutions() {
        LocalDate today = LocalDate.now();
        List<JobDefinition> definitions = jobDefinitionMapper.selectAllEnabled();
        for (JobDefinition def : definitions) {
            try {
                pgJdbcTemplate.update(
                        "INSERT INTO job_execution (job_type, trade_date, status) " +
                        "SELECT ?, ?, 'PENDING' " +
                        "WHERE NOT EXISTS (SELECT 1 FROM job_execution WHERE job_type=? AND trade_date=?)",
                        def.getJobType(), today, def.getJobType(), today);
            } catch (Exception e) {
                log.warn("init job_execution failed for {}: {}", def.getJobType(), e.getMessage());
            }
        }
        log.info("initTodayJobExecutions: {} jobs for {}", definitions.size(), today);
    }

    /**
     * 清理过期执行记录 (保留 90 天)。
     */
    @Scheduled(cron = "0 0 2 * * *")
    public void cleanupOldExecutions() {
        int deleted = pgJdbcTemplate.update(
                "DELETE FROM job_execution WHERE trade_date < CURRENT_DATE - INTERVAL '90 days'");
        log.info("cleanupOldExecutions: removed {} records", deleted);
    }

    /**
     * 标记超时无心跳的 worker 为 OFFLINE。
     */
    @Scheduled(fixedDelay = 60000)
    public void markStaleWorkersOffline() {
        LocalDateTime threshold = LocalDateTime.now().minusMinutes(5);
        int updated = workerNodeMapper.markStaleOffline(threshold);
        if (updated > 0) {
            log.info("markStaleWorkersOffline: {} workers marked OFFLINE", updated);
        }
    }
}
