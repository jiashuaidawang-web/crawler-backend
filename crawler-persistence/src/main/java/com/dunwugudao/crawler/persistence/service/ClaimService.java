package com.dunwugudao.crawler.persistence.service;

import com.dunwugudao.crawler.core.policy.ExponentialBackoffRetry;
import com.dunwugudao.crawler.core.policy.RetryPolicy;
import com.dunwugudao.crawler.persistence.entity.CrawlTask;
import com.dunwugudao.crawler.persistence.mapper.CrawlTaskMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 任务认领 / 完成 / 失败 核心服务（分布式内核）—— openGauss 版。
 *
 * <p>claim 使用 {@code FOR UPDATE SKIP LOCKED} 原生 SQL（CK 不支持，故本服务绑定 openGauss 数据源）；
 * complete/fail 的状态守卫 UPDATE 改为原生 SQL（去 MyBatis-Plus UpdateWrapper）。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ClaimService {

    private final CrawlTaskMapper crawlTaskMapper;
    private final JdbcTemplate pgJdbcTemplate;

    /** 认领一批任务，返回被置为 CLAIMED 的实体。 */
    public List<CrawlTask> claim(int batch, String nodeId) {
        return crawlTaskMapper.claim(batch, nodeId);
    }

    /** 任务成功完成（由 worker 在写入原始表与校验后调用）。 */
    public void complete(Long taskId, int actualCount) {
        complete(taskId, actualCount, null);
    }

    /** 任务成功完成，并写入耗时（毫秒）。 */
    public void complete(Long taskId, int actualCount, Long durationMs) {
        // 状态守卫：允许 CLAIMED/PENDING/RETRY → SUCCESS，避免与 retryScan 的 reclaim 竞态
        // （retryScan 可能把超时 CLAIMED 重置为 PENDING，此时 complete 仍可更新为 SUCCESS）
        // 完成后清空 worker_id，表示该任务不再被任何节点持有
        int updated = pgJdbcTemplate.update(
                "UPDATE crawl_task SET status='SUCCESS', actual_count=?, finished_at=now(), duration_ms=?, worker_id=NULL, progress_pct=100 " +
                "WHERE task_id=? AND status IN ('CLAIMED','PENDING','RETRY')",
                actualCount, durationMs, taskId);
        if (updated == 0) {
            log.warn("task {} complete 跳过（已非 CLAIMED/PENDING/RETRY，可能已被 retryScan 耗尽）", taskId);
        }
    }

    /**
     * 任务失败。根据 willRetry 与已重试次数决定：RETRY(置 next_retry_at) / DEAD(超上限) / FAILED。
     */
    public void fail(Long taskId, String err, boolean willRetry) {
        CrawlTask existing = crawlTaskMapper.selectById(taskId);
        int retryCount = (existing != null && existing.getRetryCount() != null) ? existing.getRetryCount() : 0;
        int maxRetry = (existing != null && existing.getMaxRetry() != null) ? existing.getMaxRetry() : 3;

        String status;
        LocalDateTime finishedAt;
        LocalDateTime nextRetryAt = null;

        if (willRetry && retryCount < maxRetry) {
            RetryPolicy policy = new ExponentialBackoffRetry(maxRetry, Duration.ofSeconds(2), Duration.ofMinutes(5));
            nextRetryAt = LocalDateTime.now().plus(policy.nextDelay(retryCount + 1));
            status = "RETRY";
            finishedAt = null;
        } else if (retryCount >= maxRetry) {
            status = "DEAD";
            finishedAt = LocalDateTime.now();
        } else {
            status = "FAILED";
            finishedAt = LocalDateTime.now();
        }
        String errMsg = truncate(err);
        // 状态守卫：仅当任务仍为 CLAIMED/RETRY 时才更新，避免与 retryScan 竞态
        // 失败/重试时清空 worker_id，让任务可被其他节点重新认领
        int updated = pgJdbcTemplate.update(
                "UPDATE crawl_task SET status=?, error_msg=?, retry_count=retry_count+1, " +
                "next_retry_at=?, finished_at=?, worker_id=NULL, updated_at=now() WHERE task_id=? AND status IN ('CLAIMED','RETRY')",
                status, errMsg, nextRetryAt, finishedAt, taskId);
        if (updated == 0) {
            log.warn("task {} fail 跳过（已非 CLAIMED/RETRY）", taskId);
        } else {
            log.warn("task {} -> {} (retryCount={}/{}, nextRetryAt={})",
                    taskId, status, retryCount + 1, maxRetry, nextRetryAt);
        }
    }

    private String truncate(String s) {
        if (s == null) {
            return null;
        }
        return s.length() > 500 ? s.substring(0, 500) : s;
    }
}
