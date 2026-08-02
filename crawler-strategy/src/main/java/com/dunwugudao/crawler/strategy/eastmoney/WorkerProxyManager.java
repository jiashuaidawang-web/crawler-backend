package com.dunwugudao.crawler.strategy.eastmoney;

import java.util.concurrent.atomic.AtomicBoolean;
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

    public WorkerProxyManager(Supplier<String> proxySupplier) {
        this.proxySupplier = proxySupplier;
    }

    /**
     * 获取当前可用代理。首次调用或 IP 已失效时自动提取新 IP。
     * 线程安全：并发调用只会触发一次实际提取。
     *
     * @return 代理字符串；暂时无法获取返回 null（调用方应降级或重试）
     */
    public String getProxy() {
        // 快速路径：IP 仍有效，直接返回
        if (!invalidated.get()) {
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
                log.warn("[WorkerProxyManager] proxy supplier returned null, will retry next get");
                consecutiveFailures.set(true);
                return currentProxy.get(); // 返回旧 IP（可能已坏，但比 null 好）
            }
            currentProxy.set(newProxy);
            invalidated.set(false);
            consecutiveFailures.set(false);
            log.info("[WorkerProxyManager] new proxy acquired, will be used until failure");
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
        return consecutiveFailures.get();
    }
}
