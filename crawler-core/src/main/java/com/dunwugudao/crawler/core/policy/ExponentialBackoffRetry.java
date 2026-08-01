package com.dunwugudao.crawler.core.policy;

import java.time.Duration;

/**
 * 指数退避重试策略：delay = backoffBase * 2^(attempt)，上限 backoffCap。
 */
public class ExponentialBackoffRetry implements RetryPolicy {

    private final int maxRetries;
    private final Duration backoffBase;
    private final Duration backoffCap;

    public ExponentialBackoffRetry(int maxRetries, Duration backoffBase, Duration backoffCap) {
        this.maxRetries = maxRetries;
        this.backoffBase = backoffBase;
        this.backoffCap = backoffCap;
    }

    @Override
    public Duration nextDelay(int attempt) {
        long baseMillis = backoffBase.toMillis();
        // 2^attempt，但限制位移避免溢出
        long factor = 1L << Math.min(attempt, 30);
        long delayMillis = baseMillis * factor;
        if (delayMillis > backoffCap.toMillis()) {
            delayMillis = backoffCap.toMillis();
        }
        return Duration.ofMillis(delayMillis);
    }

    @Override
    public boolean shouldRetry(int attempt, Throwable e) {
        // attempt = 已经失败的次数；小于上限即继续
        return attempt < maxRetries;
    }
}
