package com.dunwugudao.crawler.core.policy;

import java.time.Duration;

/**
 * 重试策略抽象。
 * <ul>
 *   <li>{@link #nextDelay(int)} 给定已尝试次数（attempt，从 1 计），返回下次延迟</li>
 *   <li>{@link #shouldRetry(int, Throwable)} 是否还应重试</li>
 * </ul>
 */
public interface RetryPolicy {
    Duration nextDelay(int attempt);

    boolean shouldRetry(int attempt, Throwable e);
}
