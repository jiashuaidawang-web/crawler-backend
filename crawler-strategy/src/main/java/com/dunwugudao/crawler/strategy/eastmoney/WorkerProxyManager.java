package com.dunwugudao.crawler.strategy.eastmoney;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Worker 级 IP 管理器（东财极简方案核心）。
 *
 * <p><b>设计原则：</b></p>
 * <ul>
 *   <li>1 worker 实例 = 1 个当前 IP，所有线程共享这同一个 IP。</li>
 *   <li><b>失败后才取新 IP</b>——正常情况一直用同一个，不浪费。</li>
 *   <li>IP 被标记失效后，下次 get() 自动触发重新提取。</li>
 * </ul>
 *
 * <p><b>线程安全：</b>多线程并发 get()/invalidate() 安全。失效→提取 全程加锁，
 * 避免"10 个线程同时发现 IP 坏了、同时去取 10 个新 IP"的浪费。
 * 第一个发现失效的线程负责提取，其余线程拿到同一个新 IP。</p>
 *
 * <p><b>数据完整性（不重叠、不漏）：</b>本组件只管 IP 切换。
 * 任务去重由 ClaimService 的 {@code FOR UPDATE SKIP LOCKED} 认领保证；
 * 数据幂等由 DedupWriter 的 {@code ON CONFLICT DO UPDATE} 保证。
 * IP 坏了 → 任务 fail → 回 PENDING → 下次认领用新 IP 重试，不会丢。</p>
 */
public class WorkerProxyManager {

    private static final Logger log = LoggerFactory.getLogger(WorkerProxyManager.class);

    /** 默认单 worker 最大代理获取次数（超过后停止换新 IP，避免烧完代理池）。 */
    private static final int DEFAULT_MAX_PROXY_FETCH_ATTEMPTS = 3;

    /** 当前 worker 最大代理获取次数（<=0 表示无限制，用于长效自动换 IP 代理）。 */
    private final int maxProxyFetchAttempts;

    /** 当前 IP（所有线程共享）。 */
    private final AtomicReference<String> currentProxy = new AtomicReference<>();

    /** 当前 IP 是否已失效（失效后下次 get 触发重新提取）。 */
    private final AtomicBoolean invalidated = new AtomicBoolean(true);

    /** 提取与供应商绑定（失败重试在供应商内部处理）。 */
    private final Supplier<String> proxySupplier;

    /** 失效→提取 串行化，避免多线程重复提取。 */
    private final ReentrantLock fetchLock = new ReentrantLock();

    /** 连续提取失败计数（超过阈值告警，不阻塞）。 */
    private final AtomicBoolean consecutiveFailures = new AtomicBoolean(false);

    /** 获取新 IP 的尝试次数（超过 maxProxyFetchAttempts 后停止换新）。 */
    private final AtomicInteger fetchAttemptCount = new AtomicInteger(0);

    public WorkerProxyManager(Supplier<String> proxySupplier) {
        this(proxySupplier, DEFAULT_MAX_PROXY_FETCH_ATTEMPTS);
    }

    /**
     * @param proxySupplier        代理提供者
     * @param maxProxyFetchAttempts 最大获取次数（<=0 表示无限制，适合长效自动换 IP 的代理）
     */
    public WorkerProxyManager(Supplier<String> proxySupplier, int maxProxyFetchAttempts) {
        this.proxySupplier = proxySupplier;
        this.maxProxyFetchAttempts = maxProxyFetchAttempts;
    }

    /**
     * 获取当前可用代理。首次调用或 IP 已失效时自动提取新 IP。
     * 线程安全：并发调用只会触发一次实际提取。
     * <p>超过 {@link #maxProxyFetchAttempts} 次获取后，不再换新 IP，返回旧 IP 让其失败。</p>
     *
     * @return 代理字符串；暂时无法获取返回旧 IP（可能已坏）
     */
    public String getProxy() {
        // 快速路径：IP 仍有效，直接返回
        if (!invalidated.get()) {
            return currentProxy.get();
        }

        // 超过最大获取次数 → 不再换新 IP，返回旧 IP（让它失败，避免烧代理池）
        // maxProxyFetchAttempts <= 0 表示无限制（长效自动换 IP 代理不需要手动停）
        if (maxProxyFetchAttempts > 0 && fetchAttemptCount.get() >= maxProxyFetchAttempts) {
            log.warn("[WorkerProxyManager] 已获取 {} 次新 IP，超过上限 {}，停止换新（返回旧 IP 让其失败）",
                    fetchAttemptCount.get(), maxProxyFetchAttempts);
            return currentProxy.get();
        }

        // 慢路径：需要提取新 IP（加锁串行化）
        fetchLock.lock();
        try {
            // 双重检查：可能已被其他线程提取好了
            if (!invalidated.get()) {
                return currentProxy.get();
            }
            String newProxy = proxySupplier.get();
            if (newProxy == null || newProxy.isBlank()) {
                log.warn("[WorkerProxyManager] proxy supplier returned null");
                consecutiveFailures.set(true);
                fetchAttemptCount.incrementAndGet();
                return currentProxy.get(); // 返回旧 IP（可能已坏，但比 null 好）
            }
            currentProxy.set(newProxy);
            invalidated.set(false);
            consecutiveFailures.set(false);
            int attempt = fetchAttemptCount.incrementAndGet();
            log.info("[WorkerProxyManager] new proxy acquired (attempt {}/{}), will be used until failure",
                    attempt, maxProxyFetchAttempts);
            return newProxy;
        } finally {
            fetchLock.unlock();
        }
    }

    /**
     * 标记当前 IP 失效。下次 get() 自动提取新 IP。
     * <p>调用时机：请求抛异常（连接超时、连接重置、407 等任何代理级错误）。
     * 业务错误（如东财返回 rc:102 但 HTTP 200）不应调此方法——那是数据问题不是代理问题。</p>
     */
    public void invalidate() {
        invalidated.set(true);
        log.info("[WorkerProxyManager] proxy marked invalid, will re-fetch on next get");
    }

    /** 当前是否有可用 IP（供健康检查）。 */
    public boolean hasProxy() {
        return !invalidated.get() && currentProxy.get() != null;
    }

    /** 是否处于连续失败状态（供告警）。 */
    public boolean isConsecutiveFailures() {
        return consecutiveFailures.get() || (maxProxyFetchAttempts > 0 && fetchAttemptCount.get() >= maxProxyFetchAttempts);
    }

    /** 获取当前代理（不触发提取，供监控用）。 */
    public String getCurrentProxy() {
        return currentProxy.get();
    }

    /** 获取已尝试次数（供监控用）。 */
    public int getFetchAttemptCount() {
        return fetchAttemptCount.get();
    }

    /** 重置获取次数（新 worker 启动或手动恢复时调用）。 */
    public void resetFetchAttemptCount() {
        fetchAttemptCount.set(0);
        log.info("[WorkerProxyManager] fetch attempt count reset to 0");
    }
}
