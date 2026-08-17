package com.dunwugudao.crawler.admin.seed;

import com.dunwugudao.crawler.strategy.eastmoney.EastmoneyClient;
import com.dunwugudao.crawler.strategy.eastmoney.ProxyProvider;
import com.dunwugudao.crawler.persistence.service.IpConsumptionService;
import lombok.extern.slf4j.Slf4j;

import java.net.URI;
import java.time.LocalDate;
import java.util.function.Predicate;

/**
 * 代理管理器：获取代理 + 构建请求 + 失败重试。
 *
 * <h3>响应验证</h3>
 * 不同接口成功响应的数据字段不同：
 * <ul>
 *   <li>clist 类（stockDaily / stockUniverse / boardUniverse）：响应含 {@code "total":}</li>
 *   <li>池子类（push2ex 涨跌停/强势/次新）：响应含 {@code "tc":}</li>
 * </ul>
 * 通过 {@link #CLIST_VALIDATOR} / {@link #POOL_VALIDATOR} 区分，避免池子响应被误判为"502 无有效数据"。
 *
 * <h3>职责</h3>
 * <ul>
 *   <li>从 {@link ProxyProvider}(青果)获取代理</li>
 *   <li>解析代理字符串(支持 {@code ip:port} 和 {@code user:pass@ip:port})</li>
 *   <li>发送 HTTP 请求,失败自动重试(最多 {@link #MAX_RETRIES} 次)</li>
 * </ul>
 *
 * <h3>为什么不直接用 OkHttp 的 Proxy + Authenticator</h3>
 * <p>OkHttp 的 {@code Proxy(Type.HTTP, InetSocketAddress)} 不会自动从 URL 提取 userinfo,
 * 需要手动设置 {@code ProxyAuthenticator},但某些代理服务器对认证头格式敏感,容易 407。
 * 而 {@link EastmoneyClient} 接收 {@code http://user:pass@ip:port} 格式,
 * 内部自动提取 userinfo 并设置认证头,兼容性更好(已验证 Python requests 也是这种格式)。</p>
 *
 * <h3>装配</h3>
 * <p>本类为 spring-free 的纯 POJO,{@link SeedStrategyBeans} 手动装配,{@code @Component}。</p>
 *
 * @see SeedStrategyBeans
 * @see com.dunwugudao.crawler.strategy.eastmoney.QgLongTermProxyProvider
 */
@Slf4j
public class ProxyManager {

    /** 单任务最大重试次数(获取新 IP 重试) — 40% 成功率下,10 次期望 4 次成功 */
    private static final int MAX_RETRIES = 10;

    /**
     * 全局连续失败熔断阈值。
     * <p>当所有任务的累计连续失败次数达到此值，说明代理池整体被目标站点封禁（如东财封了整段 IP），
     * 此时不再浪费代理 IP，直接返回 null 让上层快速失败。
     * <p>熔断后需人工介入（换代理供应商 / 等封禁解除），或等待 {@link #CIRCUIT_BREAKER_RESET_MS} 后自动半开。</p>
     */
    private static final int CIRCUIT_BREAKER_THRESHOLD = 35;

    /** 熔断器自动半开时间（毫秒）— 30 分钟后尝试恢复 */
    private static final long CIRCUIT_BREAKER_RESET_MS = 30 * 60 * 1000L;

    /** 每阶段 IP 告警阈值:超过此次数打 WARN */
    private static final int IP_WARNING_THRESHOLD = 50;

    /** 连续失败计数（达到阈值触发熔断） */
    private int consecutiveFailures = 0;

    /** 当前阶段 IP 消耗计数(上层每阶段开始时 reset) */
    private int stageIpCount = 0;

    /** 缓存的代理:复用直到失效再换。失败时置 null,下次 acquire 重新提取。 */
    private String cachedProxy = null;

    /** 熔断开始时间（用于自动半开） */
    private long circuitBreakerOpenTime = 0L;

    /** 是否处于熔断状态 */
    private boolean circuitBreakerOpen = false;

    /** 当前请求上下文(IP 埋点用),由调用方在请求前设置。 */
    private String currentStage = "UNKNOWN";
    private String currentTaskType = "UNKNOWN";
    private LocalDate currentTradeDate = LocalDate.now();

    public void setCurrentContext(String stage, String taskType, LocalDate tradeDate) {
        this.currentStage = stage != null ? stage : "UNKNOWN";
        this.currentTaskType = taskType != null ? taskType : "UNKNOWN";
        this.currentTradeDate = tradeDate != null ? tradeDate : LocalDate.now();
    }

    /** clist 类接口响应验证：rc=0 且含 total 字段 */
    public static final Predicate<String> CLIST_VALIDATOR =
            resp -> resp.contains("\"rc\":0") && resp.contains("\"total\":");

    /** 池子类接口响应验证：rc=0 且含 tc 字段 */
    public static final Predicate<String> POOL_VALIDATOR =
            resp -> resp.contains("\"rc\":0") && resp.contains("\"tc\":");

    /** 代理提供者(青果) */
    private final ProxyProvider proxyProvider;
    private final IpConsumptionService ipConsumptionService;

    /** 东已验证的 HTTP 客户端(代理认证已兼容) */
    private final EastmoneyClient eastmoneyClient = new EastmoneyClient();

    /** 由 SeedStrategyBeans 注入(从配置读取 trade_no/sign/city)。 */
    public ProxyManager(ProxyProvider proxyProvider, IpConsumptionService ipConsumptionService) {
        this.proxyProvider = proxyProvider;
        this.ipConsumptionService = ipConsumptionService;
        log.info("[ProxyManager] 初始化完成(proxyProvider={}, maxRetries={}, client=EastmoneyClient)",
                proxyProvider.getClass().getSimpleName(), MAX_RETRIES);
    }

    /**
     * 执行带代理的 HTTP GET 请求,失败自动重试。
     * <p>响应验证使用 {@link #CLIST_VALIDATOR}（rc=0 且含 total）。
     * 池子类接口请用 {@link #executeWithRetry(String, Predicate)} 传入 {@link #POOL_VALIDATOR}。</p>
     *
     * @param url 目标 URL(完整 URL,含参数)
     * @return 响应体文本;所有重试失败返回 null
     */
    public String executeWithRetry(String url) {
        return executeWithRetry(url, CLIST_VALIDATOR);
    }

    /**
     * 执行带代理的 HTTP GET 请求,失败自动重试。
     *
     * <h4>重试逻辑</h4>
     * <ol>
     *   <li>从 provider 获取一个新代理</li>
     *   <li>用 {@link EastmoneyClient} 发送请求(自动处理代理认证)</li>
     *   <li>按 responseValidator 验证响应 → 成功返回</li>
     *   <li>失败(407/502/IO 异常/空响应/数据无效) → 换下一个代理重试</li>
     * </ol>
     *
     * @param url               目标 URL(完整 URL,含参数)
     * @param responseValidator 响应有效性校验（clist 用 {@link #CLIST_VALIDATOR}，池子用 {@link #POOL_VALIDATOR}）
     * @return 响应体文本;所有重试失败返回 null
     */
    public String executeWithRetry(String url, Predicate<String> responseValidator) {
        // ---------- 熔断器检查 ----------
        if (circuitBreakerOpen) {
            long elapsed = System.currentTimeMillis() - circuitBreakerOpenTime;
            if (elapsed < CIRCUIT_BREAKER_RESET_MS) {
                log.warn("[ProxyManager] 熔断器开启中, 拒绝请求({}ms/{}ms), url={}",
                        elapsed, CIRCUIT_BREAKER_RESET_MS, url);
                return null;
            }
            // 超过重置时间，半开：允许一次尝试
            log.info("[ProxyManager] 熔断器半开, 尝试恢复, url={}", url);
            circuitBreakerOpen = false;
            consecutiveFailures = 0;
        }

        log.info("[ProxyManager] 开始请求, url={}, maxRetries={}, consecutiveFailures={}/{}",
                url, MAX_RETRIES, consecutiveFailures, CIRCUIT_BREAKER_THRESHOLD);

        for (int attempt = 1; attempt <= MAX_RETRIES; attempt++) {
            // ---------- 1. 获取代理 ----------
            String proxyStr = acquireProxy();
            if (proxyStr == null) {
                log.warn("[ProxyManager] attempt={}/{}, 获取代理失败(返回 null), 换下一个", attempt, MAX_RETRIES);
                consecutiveFailures++;
                checkCircuitBreaker();
                continue;
            }

            // ---------- 2. 发送请求(EastmoneyClient,自动处理代理认证) ----------
            long start = System.currentTimeMillis();
            try {
                String resp = eastmoneyClient.get(url, randomUa(), proxyStr);
                int latency = (int) (System.currentTimeMillis() - start);

                // ---------- 3. 验证响应 ----------
                if (resp == null || resp.isEmpty()) {
                    log.warn("[ProxyManager] attempt={}/{}, 空响应, latency={}ms, proxy={}, 换下一个代理",
                            attempt, MAX_RETRIES, latency, extractProxyHost(proxyStr));
                    consecutiveFailures++;
                    checkCircuitBreaker();
                    ipConsumptionService.log("ADMIN", currentStage, currentTaskType, null,
                            extractProxyHost(proxyStr), "EMPTY", 0, (long)latency, "empty response", currentTradeDate);
                    cachedProxy = null;  // 失效缓存,下次换新代理
                    continue;
                }

                // 东财 CDN 特殊行为:返回 502 但响应体是有效数据(rc=0 + 数据字段)
                boolean hasValidData = responseValidator.test(resp);
                if (!hasValidData && (resp.contains("502") || resp.contains("Bad Gateway"))) {
                    log.warn("[ProxyManager] attempt={}/{}, 502 错误(无有效数据), latency={}ms, resp={}, 换下一个代理",
                            attempt, MAX_RETRIES, latency, resp.substring(0, Math.min(200, resp.length())));
                    consecutiveFailures++;
                    checkCircuitBreaker();
                    cachedProxy = null;  // 失效缓存,下次换新代理
                    continue;
                }

                if (hasValidData && resp.contains("502")) {
                    log.info("[ProxyManager] attempt={}/{}, 东财 CDN 返回 502 但数据有效, 当作成功处理, latency={}ms",
                            attempt, MAX_RETRIES, latency);
                }

                // ---------- 4. 成功 → 重置连续失败计数 ----------
                log.info("[ProxyManager] attempt={}/{}, 请求成功! latency={}ms, respLen={}, proxy={}",
                        attempt, MAX_RETRIES, latency, resp.length(), extractProxyHost(proxyStr));
                consecutiveFailures = 0;
                // IP 消耗埋点（admin 端探测）
                ipConsumptionService.log("ADMIN", currentStage, currentTaskType, null,
                        extractProxyHost(proxyStr), "SUCCESS", resp.length(), (long)latency, null, currentTradeDate);
                return resp;

            } catch (Exception e) {
                // ---------- 5. 失败,换下一个 ----------
                int latency = (int) (System.currentTimeMillis() - start);
                log.warn("[ProxyManager] attempt={}/{}, 请求异常, latency={}ms, error={}, 换下一个代理",
                        attempt, MAX_RETRIES, latency, e.getMessage());
                consecutiveFailures++;
                checkCircuitBreaker();
                // IP 消耗埋点（失败）
                ipConsumptionService.log("ADMIN", currentStage, currentTaskType, null,
                        extractProxyHost(proxyStr), "FAILED", 0, (long)latency, e.getMessage(), currentTradeDate);
                cachedProxy = null;  // 失效缓存,下次换新代理
            }
        }

        // ---------- 6. 所有重试耗尽 ----------
        log.error("[ProxyManager] 请求最终失败, 已重试 {} 次, url={}", MAX_RETRIES, url);
        return null;
    }

    /**
     * 检查是否需要触发熔断。
     * <p>当连续失败次数达到 {@link #CIRCUIT_BREAKER_THRESHOLD}，开启熔断器，
     * 后续请求直接返回 null 直到 {@link #CIRCUIT_BREAKER_RESET_MS} 后自动半开。</p>
     */
    private void checkCircuitBreaker() {
        if (!circuitBreakerOpen && consecutiveFailures >= CIRCUIT_BREAKER_THRESHOLD) {
            circuitBreakerOpen = true;
            circuitBreakerOpenTime = System.currentTimeMillis();
            log.error("[ProxyManager] ⚠️ 熔断器开启! 连续失败 {} 次, {}ms 内不再请求代理, 请检查代理池是否被封禁",
                    consecutiveFailures, CIRCUIT_BREAKER_RESET_MS);
        }
    }

    // ========================================================================
    // 私有方法:获取代理
    // ========================================================================

    /**
     * 从青果提取 1 个代理。
     *
     * @return 代理字符串(形如 {@code "http://user:pass@ip:port"});失败返回 null
     */
    public String acquireProxy() {
        // 复用缓存代理(直到失效再换),避免每个请求都烧一个新 IP
        if (cachedProxy != null) {
            log.debug("[acquireProxy] 复用缓存代理: {}", extractProxyHost(cachedProxy));
            return cachedProxy;
        }
        log.debug("[acquireProxy] 开始从青果获取代理");
        try {
            String proxy = proxyProvider.acquire();
            if (proxy == null) {
                log.warn("[acquireProxy] provider 返回 null");
            } else {
                cachedProxy = proxy;  // 缓存,后续请求复用
                stageIpCount++;
                // 超过阈值打 WARN,提醒正在大量烧 IP
                if (stageIpCount == IP_WARNING_THRESHOLD) {
                    log.warn("[ProxyManager] 本阶段已消耗 {} 个 IP,超过告警阈值 {},请检查是否有优化空间",
                            stageIpCount, IP_WARNING_THRESHOLD);
                }
                log.info("[acquireProxy] 获取代理成功: {}(本阶段第 {} IP)", extractProxyHost(proxy), stageIpCount);
            }
            return proxy;
        } catch (Exception e) {
            log.error("[ProxyManager] provider 异常: {}", e.getMessage(), e);
            return null;
        }
    }

    /** 获取当前阶段 IP 消耗数(供统计用)。 */
    public int getStageIpCount() {
        return stageIpCount;
    }

    /** 重置当前阶段 IP 消耗计数(每阶段开始时调用)。 */
    public void resetStageIpCount() {
        stageIpCount = 0;
        cachedProxy = null;  // 新阶段清缓存,重新提取代理
    }

    // ========================================================================
    // 私有方法:工具
    // ========================================================================

    /** 从代理字符串提取 host:port(用于日志,脱敏) */
    private String extractProxyHost(String proxy) {
        if (proxy == null) return "(null)";
        try {
            URI uri = URI.create(proxy);
            return uri.getHost() + ":" + uri.getPort();
        } catch (Exception e) {
            return proxy;
        }
    }

    /** 随机 User-Agent */
    private static String randomUa() {
        return "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Safari/537.36";
    }

    // ========================================================================
    // 熔断器状态查询 / 手动控制
    // ========================================================================

    /** 查询熔断器当前状态（健康检查用） */
    public String getCircuitBreakerStatus() {
        if (circuitBreakerOpen) {
            long elapsed = System.currentTimeMillis() - circuitBreakerOpenTime;
            long remaining = Math.max(0, CIRCUIT_BREAKER_RESET_MS - elapsed);
            return String.format("OPEN(连续失败=%d, 剩余=%ds)", consecutiveFailures, remaining / 1000);
        }
        return String.format("CLOSED(连续失败=%d/%d)", consecutiveFailures, CIRCUIT_BREAKER_THRESHOLD);
    }

    /** 手动重置熔断器（换代理供应商后调用） */
    public void resetCircuitBreaker() {
        boolean wasOpen = circuitBreakerOpen;
        circuitBreakerOpen = false;
        consecutiveFailures = 0;
        circuitBreakerOpenTime = 0L;
        log.info("[ProxyManager] 熔断器手动重置(wasOpen={})", wasOpen);
    }
}
