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

    /** 当前 IP（所有线程共享）。 */
    private final AtomicReference<String> currentProxy = new AtomicReference<>();

    /** 当前 IP 是否已失效（失效后下次 get 触发重新提取）。 */
    private final AtomicBoolean invalidated = new AtomicBoolean(true);

    /** 提取与供应商绑定（失败重试在供应商内部处理）。 */
    private final Supplier<String> proxySupplier;

    /** 失效→提取 串行化，避免多线程重复提取。 */
    private final ReentrantLock fetchLock = new ReentrantLock();

    /** 连续失败计数（达到阈值触发熔断）。 */
    private final AtomicInteger consecutiveFailures = new AtomicInteger(0);

    /** 熔断开始时间（用于自动半开）。 */
    private long circuitBreakerOpenTime = 0L;

    /** 是否处于熔断状态。 */
    private boolean circuitBreakerOpen = false;

    /** 连续失败熔断阈值。当连续失败达到此值，说明代理池整体被封禁，停止浪费 IP。 */
    private static final int CIRCUIT_BREAKER_THRESHOLD = 20;

    /** 熔断器自动半开时间（毫秒）— 10 分钟后尝试恢复。 */
    private static final long CIRCUIT_BREAKER_RESET_MS = 10 * 60 * 1000L;

    /** 永久失效检测:熔断器连续开启达到此次数后永久停止,直到人工手动重置。 */
    private static final int PERMANENT_TRIP_THRESHOLD = 3;

    /** 熔断器连续开启次数(每次从半开→再次开启计数+1)。 */
    private int permanentTripCount = 0;

    /** 是否已永久停止(代理池永久失效,需人工在监控页面手动重置)。 */
    private boolean permanentlyStopped = false;

    public WorkerProxyManager(Supplier<String> proxySupplier) {
        this.proxySupplier = proxySupplier;
    }

    /**
     * 获取当前可用代理。首次调用或 IP 已失效时自动提取新 IP。
     * 线程安全：并发调用只会触发一次实际提取。
     * <p>熔断器开启时返回 null,任务应快速失败。</p>
     *
     * @return 代理字符串；熔断/无可用 IP 时返回 null
     */
    public String getProxy() {
        // ---------- 永久停止检查:代理池永久失效,需人工在监控页面手动重置 ----------
        if (permanentlyStopped) {
            log.warn("[WorkerProxyManager] 已永久停止(连续熔断 {} 次),拒绝提取新 IP,请在监控页面手动重置熔断器",
                    permanentTripCount);
            return null;
        }

        // ---------- 熔断器检查 ----------
        if (circuitBreakerOpen) {
            long elapsed = System.currentTimeMillis() - circuitBreakerOpenTime;
            if (elapsed < CIRCUIT_BREAKER_RESET_MS) {
                log.warn("[WorkerProxyManager] 熔断器开启中, 拒绝提取新 IP({}ms/{}ms), 连续失败 {} 次, url 级将快速失败",
                        elapsed, CIRCUIT_BREAKER_RESET_MS, consecutiveFailures.get());
                return null;
            }
            // 超过重置时间，半开：允许一次尝试
            log.info("[WorkerProxyManager] 熔断器半开, 尝试恢复提取代理");
            circuitBreakerOpen = false;
            consecutiveFailures.set(0);
        }

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
                log.warn("[WorkerProxyManager] proxy supplier returned null");
                consecutiveFailures.incrementAndGet();
                checkCircuitBreaker();
                return currentProxy.get(); // 返回旧 IP（可能已坏，但比 null 好）
            }
            currentProxy.set(newProxy);
            invalidated.set(false);
            log.info("[WorkerProxyManager] new proxy acquired, proxy={}, will be used until failure",
                    newProxy);
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
        // 熔断器已开启时不再重复计数——熔断器本身就是兜底,任务级应快速失败,
        // 避免「任务级重试(10次)+ 管理器级熔断(50次)」叠加成几百次浪费。
        if (!circuitBreakerOpen) {
            consecutiveFailures.incrementAndGet();
            checkCircuitBreaker();
        }
        log.info("[WorkerProxyManager] proxy marked invalid, will re-fetch on next get(连续失败={}/{})",
                consecutiveFailures.get(), CIRCUIT_BREAKER_THRESHOLD);
    }

    /**
     * 请求成功时调用,重置连续失败计数(说明代理池恢复可用)。
     * <p>在 EastmoneyApiStrategy 每次成功拿到响应后调用。</p>
     */
    public void onSuccess() {
        consecutiveFailures.set(0);
    }

    /** 当前是否有可用 IP（供健康检查）。 */
    public boolean hasProxy() {
        return !invalidated.get() && currentProxy.get() != null;
    }

    /** 是否处于连续失败/熔断状态（供告警）。 */
    public boolean isConsecutiveFailures() {
        return circuitBreakerOpen || consecutiveFailures.get() >= CIRCUIT_BREAKER_THRESHOLD;
    }

    // ========================================================================
    // 熔断器
    // ========================================================================

    /** 检查是否需要触发熔断。 */
    private void checkCircuitBreaker() {
        if (!circuitBreakerOpen && consecutiveFailures.get() >= CIRCUIT_BREAKER_THRESHOLD) {
            circuitBreakerOpen = true;
            circuitBreakerOpenTime = System.currentTimeMillis();
            // 永久失效检测:每次熔断开启计数+1
            permanentTripCount++;
            if (permanentTripCount >= PERMANENT_TRIP_THRESHOLD) {
                permanentlyStopped = true;
                log.error("[WorkerProxyManager] ⛔ 代理池永久失效! 连续熔断 {} 次(阈值 {}),永久停止提取新 IP,请在监控页面手动重置熔断器",
                        permanentTripCount, PERMANENT_TRIP_THRESHOLD);
            } else {
                log.error("[WorkerProxyManager] ⚠️ 熔断器开启! 连续失败 {} 次, {}ms 内不再提取新代理(第 {} 次熔断,达 {} 次将永久停止)",
                        consecutiveFailures.get(), CIRCUIT_BREAKER_RESET_MS, permanentTripCount, PERMANENT_TRIP_THRESHOLD);
            }
        }
    }

    /** 查询熔断器当前状态（健康检查/监控用）。 */
    public String getCircuitBreakerStatus() {
        if (permanentlyStopped) {
            return String.format("PERMANENTLY_STOPPED(连续熔断 %d 次,需人工重置)", permanentTripCount);
        }
        if (circuitBreakerOpen) {
            long elapsed = System.currentTimeMillis() - circuitBreakerOpenTime;
            long remaining = Math.max(0, CIRCUIT_BREAKER_RESET_MS - elapsed);
            return String.format("OPEN(连续失败=%d, 剩余=%ds, 累计熔断=%d/%d)", consecutiveFailures.get(),
                    remaining / 1000, permanentTripCount, PERMANENT_TRIP_THRESHOLD);
        }
        return String.format("CLOSED(连续失败=%d/%d, 累计熔断=%d/%d)", consecutiveFailures.get(),
                CIRCUIT_BREAKER_THRESHOLD, permanentTripCount, PERMANENT_TRIP_THRESHOLD);
    }

    /** 是否已永久停止(监控页面据此显示告警和重置按钮)。 */
    public boolean isPermanentlyStopped() {
        return permanentlyStopped;
    }

    /** 获取累计熔断次数(监控用)。 */
    public int getPermanentTripCount() {
        return permanentTripCount;
    }

    /** 手动重置熔断器（换代理供应商/修复代理池后在监控页面调用）。 */
    public void resetCircuitBreaker() {
        boolean wasOpen = circuitBreakerOpen;
        circuitBreakerOpen = false;
        consecutiveFailures.set(0);
        circuitBreakerOpenTime = 0L;
        // 同时重置永久失效计数,允许重新尝试
        permanentTripCount = 0;
        permanentlyStopped = false;
        log.info("[WorkerProxyManager] 熔断器手动重置(wasOpen={}, 永久失效计数已清零)", wasOpen);
    }
}
