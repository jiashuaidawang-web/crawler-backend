package com.dunwugudao.crawler.persistence.service;

import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.dunwugudao.crawler.core.policy.ExponentialBackoffRetry;
import com.dunwugudao.crawler.core.policy.RetryPolicy;
import com.dunwugudao.crawler.persistence.entity.CrawlTask;
import com.dunwugudao.crawler.persistence.mapper.CrawlTaskMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 任务认领 / 完成 / 失败 核心服务（分布式内核）。
 * <p>claim 使用 SKIP LOCKED 原生 SQL；complete/fail 仅更新必要的列（避免覆盖其他字段）。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ClaimService {

    private final CrawlTaskMapper crawlTaskMapper;

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
        CrawlTask t = new CrawlTask();
        t.setStatus("SUCCESS");
        t.setActualCount(actualCount);
        t.setFinishedAt(LocalDateTime.now());
        if (durationMs != null) {
            t.setDurationMs(durationMs);
        }
        // 状态守卫：仅当任务仍为 CLAIMED 时才置 SUCCESS，避免与 retryScan 的 reclaim/promote 竞态
        int updated = crawlTaskMapper.update(t,
                new UpdateWrapper<CrawlTask>().eq("task_id", taskId).eq("status", "CLAIMED"));
        if (updated == 0) {
            log.warn("task {} complete 跳过（已非 CLAIMED，可能被 retryScan 回收/耗尽）", taskId);
        }
    }

    /**
     * 任务失败。根据 willRetry 与已重试次数决定：RETRY(置 next_retry_at) / DEAD(超上限) / FAILED。
     */
    public void fail(Long taskId, String err, boolean willRetry) {
        CrawlTask existing = crawlTaskMapper.selectById(taskId);
        int retryCount = (existing != null && existing.getRetryCount() != null) ? existing.getRetryCount() : 0;
        int maxRetry = (existing != null && existing.getMaxRetry() != null) ? existing.getMaxRetry() : 3;

        CrawlTask t = new CrawlTask();
        t.setErrorMsg(truncate(err));
        t.setRetryCount(retryCount + 1);

        if (willRetry && retryCount < maxRetry) {
            // 指数退避：延迟后再可被认领，避免立刻重试烧代理 IP（2s/4s/8s...）
            RetryPolicy policy = new ExponentialBackoffRetry(maxRetry, Duration.ofSeconds(2), Duration.ofMinutes(5));
            LocalDateTime next = LocalDateTime.now().plus(policy.nextDelay(t.getRetryCount()));
            t.setStatus("RETRY");
            t.setNextRetryAt(next);
        } else if (retryCount >= maxRetry) {
            // 耗尽重试 → DEAD，不再被认领，需人工介入
            t.setStatus("DEAD");
            t.setFinishedAt(LocalDateTime.now());
        } else {
            t.setStatus("FAILED");
            t.setFinishedAt(LocalDateTime.now());
        }
        // 状态守卫：仅当任务仍为 CLAIMED/RETRY 时才更新，避免与 retryScan 竞态
        crawlTaskMapper.update(t,
                new UpdateWrapper<CrawlTask>().eq("task_id", taskId).in("status", "CLAIMED", "RETRY"));
        log.warn("task {} -> {} (retryCount={}/{}, nextRetryAt={})",
                taskId, t.getStatus(), t.getRetryCount(), maxRetry, t.getNextRetryAt());
        log.warn("task {} -> {} (retryCount={}/{})", taskId, t.getStatus(), t.getRetryCount(), maxRetry);
    }

    private String truncate(String s) {
        if (s == null) {
            return null;
        }
        return s.length() > 500 ? s.substring(0, 500) : s;
    }
}
