package com.dunwugudao.crawler.core.model;

import com.dunwugudao.crawler.core.policy.RetryPolicy;
import lombok.Data;

import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 策略执行的上下文：任务 + 策略级配置 + 重试策略。
 * <p>strategyConfig 通常来自 CrawlTask.paramsJson 的反序列化结果（页码/股票/日期区间等）。</p>
 */
@Data
public class CrawlContext {
    private CrawlTask task;
    private Map<String, Object> strategyConfig;
    private RetryPolicy retryPolicy;

    /**
     * 任务级代理 IP 使用计数（本任务已尝试过的 IP 个数）。
     * <p>由 {@link com.dunwugudao.crawler.strategy.eastmoney.EastmoneyApiStrategy} 维护，
     * 一个任务最多用 {@link com.dunwugudao.crawler.strategy.eastmoney.EastmoneyApiStrategy#MAX_PROXY_FETCH_ATTEMPTS_PER_TASK} 个 IP，
     * 超限后不再换新 IP、直接失败，交 worker 走 RETRY/DEAD。worker 接新任务时 CrawlContext 重建，计数归零。</p>
     */
    private final AtomicInteger proxyFetchCount = new AtomicInteger(0);
}
