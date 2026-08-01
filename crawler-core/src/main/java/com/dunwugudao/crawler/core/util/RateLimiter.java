package com.dunwugudao.crawler.core.util;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 简单令牌桶限流器（线程安全）。
 * <ul>
 *   <li>{@link #acquire()} 阻塞直到拿到令牌</li>
 *   <li>{@link #tryAcquire()} 非阻塞，拿不到立即返回 false</li>
 * </ul>
 * 用于反爬限速（配合 AntiCrawlConfig.rateLimitPerSec）。
 */
public class RateLimiter {

    private final double permitsPerSecond;
    private final double maxPermits;
    private double storedPermits;
    private long nextFreeTicketMicros;
    private final Object lock = new Object();

    public RateLimiter(double permitsPerSecond) {
        this(permitsPerSecond, permitsPerSecond);
    }

    public RateLimiter(double permitsPerSecond, double maxBurst) {
        this.permitsPerSecond = permitsPerSecond;
        this.maxPermits = Math.max(1.0, maxBurst);
        this.storedPermits = this.maxPermits;
        this.nextFreeTicketMicros = System.nanoTime() / 1000L;
    }

    /** 阻塞获取 1 个令牌。 */
    public void acquire() {
        acquire(1);
    }

    /** 阻塞获取 permits 个令牌。 */
    public void acquire(int permits) {
        long waitMicros = reserve(permits);
        if (waitMicros > 0) {
            try {
                TimeUnit.MICROSECONDS.sleep(waitMicros);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    /** 非阻塞尝试获取 1 个令牌。 */
    public boolean tryAcquire() {
        synchronized (lock) {
            refill();
            if (storedPermits >= 1.0) {
                storedPermits -= 1.0;
                return true;
            }
            return false;
        }
    }

    private long reserve(int permits) {
        synchronized (lock) {
            refill();
            long waitMicros;
            if (storedPermits >= permits) {
                storedPermits -= permits;
                waitMicros = 0;
            } else {
                double missing = permits - storedPermits;
                storedPermits = 0;
                waitMicros = (long) (missing / permitsPerSecond * 1_000_000.0);
                nextFreeTicketMicros = nowMicros() + waitMicros;
            }
            return waitMicros;
        }
    }

    private void refill() {
        long now = nowMicros();
        if (now > nextFreeTicketMicros) {
            double newPermits = (now - nextFreeTicketMicros) / 1_000_000.0 * permitsPerSecond;
            storedPermits = Math.min(maxPermits, storedPermits + newPermits);
            nextFreeTicketMicros = now;
        }
    }

    private long nowMicros() {
        return System.nanoTime() / 1000L;
    }
}
