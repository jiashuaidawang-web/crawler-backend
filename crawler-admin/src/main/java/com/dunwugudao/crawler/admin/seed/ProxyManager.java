package com.dunwugudao.crawler.admin.seed;

import com.dunwugudao.crawler.strategy.eastmoney.ProxyProvider;
import lombok.extern.slf4j.Slf4j;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

/**
 * 代理管理器：获取代理 + 构建请求 + 失败重试。
 *
 * <h3>职责</h3>
 * <ul>
 *   <li>从 {@link ProxyProvider}(巨量)获取代理</li>
 *   <li>解析代理字符串(支持白名单模式 {@code ip:port} 和账号密码模式 {@code user:pass@ip:port})</li>
 *   <li>发送 HTTP 请求,失败自动重试(最多 {@link #MAX_RETRIES} 次)</li>
 * </ul>
 *
 * <h3>为什么手写 parseProxy 而不是用 URI.create</h3>
 * <p>Apache HttpClient 的 {@code URI.create("http://ip:port")} 会把 {@code ip:port} 误判为 userinfo,
 * 自动添加 {@code Proxy-Authorization} 头,导致 407 错误。
 * 所以这里手动 split,完全不走 URI.create。</p>
 *
 * <h3>装配</h3>
 * <p>本类为 spring-free 的纯 POJO,{@link SeedStrategyBeans} 手动装配,{@code @Component}。</p>
 *
 * @see SeedStrategyBeans
 * @see JuliangProxyProvider
 */
@Slf4j
public class ProxyManager {

    /** 单任务最大重试次数(获取新 IP 重试) */
    private static final int MAX_RETRIES = 50;

    /** 代理提供者(巨量) */
    private final ProxyProvider proxyProvider;

    /** OkHttp 客户端(复用,自动禁用重定向,跟 Python requests 行为一致) */
    private final OkHttpClient okHttpClient;

    /**
     * 构造器注入(由 SeedStrategyBeans 装配)。
     *
     * @param proxyProvider 代理提供者,从配置读取 trade_no/sign/city
     */
    public ProxyManager(ProxyProvider proxyProvider) {
        this.proxyProvider = proxyProvider;

        // OkHttp 默认不自动重定向,不自动添加认证头,跟 Python requests 行为一致
        this.okHttpClient = new OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .followRedirects(false)  // 禁用自动重定向(避免 HTTP→HTTPS 升级导致 407)
                .followSslRedirects(false)
                .build();

        log.info("[ProxyManager] 初始化完成(proxyProvider={}, maxRetries={}, client=OkHttp)",
                proxyProvider.getClass().getSimpleName(), MAX_RETRIES);
    }

    /**
     * 执行带代理的 HTTP GET 请求,失败自动重试。
     *
     * <h4>重试逻辑</h4>
     * <ol>
     *   <li>从 provider 获取一个新代理</li>
     *   <li>解析代理(手动 split,避免 URI.create 误判)</li>
     *   <li>发送请求</li>
     *   <li>成功 → 返回响应</li>
     *   <li>失败(407/502/IO 异常/空响应) → 换下一个代理重试</li>
     * </ol>
     *
     * @param url 目标 URL(完整 URL,含参数)
     * @return 响应体文本;所有重试失败返回 null
     */
    public String executeWithRetry(String url) {
        log.info("[ProxyManager] 开始请求, url={}, maxRetries={}", url, MAX_RETRIES);

        for (int attempt = 1; attempt <= MAX_RETRIES; attempt++) {
            // ---------- 1. 获取代理 ----------
            log.debug("[ProxyManager] attempt={}/{}, 开始获取代理", attempt, MAX_RETRIES);
            String proxyStr = acquireProxy();
            if (proxyStr == null) {
                log.warn("[ProxyManager] attempt={}/{}, 获取代理失败(返回 null), 换下一个", attempt, MAX_RETRIES);
                continue;
            }
            log.info("[ProxyManager] attempt={}/{}, 获取到代理 raw={}", attempt, MAX_RETRIES, proxyStr);

            // ---------- 2. 解析代理 ----------
            ParsedProxy parsed = parseProxy(proxyStr);
            String proxyInfo = extractProxyInfo(proxyStr);
            log.info("[ProxyManager] attempt={}/{}, 解析代理结果: host={}, port={}, hasAuth={}",
                    attempt, MAX_RETRIES, parsed.host, parsed.port, (parsed.user != null));

            // ---------- 3. 发送请求(OkHttp) ----------
            long start = System.currentTimeMillis();
            try {
                // 每次请求用新 client(代理不同),但复用连接池
                OkHttpClient client = okHttpClient.newBuilder()
                        .proxy(new java.net.Proxy(java.net.Proxy.Type.HTTP,
                                new java.net.InetSocketAddress(parsed.host, parsed.port)))
                        .build();

                Request request = new Request.Builder()
                        .url(url)
                        .header("Referer", "https://quote.eastmoney.com/center/")
                        .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                        .build();

                log.info("[ProxyManager] attempt={}/{}, 开始发送请求, proxy={}", attempt, MAX_RETRIES, proxyInfo);

                try (Response response = client.newCall(request).execute()) {
                    int latency = (int) (System.currentTimeMillis() - start);
                    ResponseBody body = response.body();
                    String resp = body != null ? body.string() : "";

                    // ---------- 4. 验证响应 ----------
                    if (resp.isEmpty()) {
                        log.warn("[ProxyManager] attempt={}/{}, 空响应, latency={}ms, 换下一个代理",
                                attempt, MAX_RETRIES, latency);
                        continue;
                    }

                    // 东财 CDN 特殊行为:返回 502 但响应体是有效数据(rc=0 + data.total>0)
                    boolean hasValidData = resp.contains("\"rc\":0") && resp.contains("\"total\":");
                    if (!hasValidData && (resp.contains("502") || resp.contains("Bad Gateway"))) {
                        log.warn("[ProxyManager] attempt={}/{}, 502 错误(无有效数据), latency={}ms, resp={}, 换下一个代理",
                                attempt, MAX_RETRIES, latency, resp.substring(0, Math.min(200, resp.length())));
                        continue;
                    }

                    if (hasValidData && resp.contains("502")) {
                        log.info("[ProxyManager] attempt={}/{}, 东财 CDN 返回 502 但数据有效, 当作成功处理, latency={}ms",
                                attempt, MAX_RETRIES, latency);
                    }

                    // ---------- 5. 成功 ----------
                    log.info("[ProxyManager] attempt={}/{}, 请求成功! latency={}ms, respLen={}, proxy={}",
                            attempt, MAX_RETRIES, latency, resp.length(), proxyInfo);
                    return resp;
                }

            } catch (Exception e) {
                // ---------- 6. 失败,换下一个 ----------
                int latency = (int) (System.currentTimeMillis() - start);
                log.warn("[ProxyManager] attempt={}/{}, 请求异常, latency={}ms, error={}, 换下一个代理",
                        attempt, MAX_RETRIES, latency, e.getMessage());
            }
        }

        // ---------- 7. 所有重试耗尽 ----------
        log.error("[ProxyManager] 请求最终失败, 已重试 {} 次, url={}", MAX_RETRIES, url);
        return null;
    }

    // ========================================================================
    // 内部类:代理解析结果
    // ========================================================================

    /** 代理解析结果 */
    private static class ParsedProxy {
        String host;   // 代理 IP
        int port;      // 代理端口(-1 表示解析失败)
        String user;   // 用户名(白名单模式为 null)
        String pass;   // 密码(白名单模式为 null)
    }

    // ========================================================================
    // 私有方法:代理解析
    // ========================================================================

    /**
     * 手动解析代理字符串,不走 URI.create(避免 Apache 自动从 userinfo 提取认证)。
     *
     * <h4>支持格式</h4>
     * <ul>
     *   <li>{@code ip:port} — 白名单模式(无认证)</li>
     *   <li>{@code http://ip:port} — 白名单模式</li>
     *   <li>{@code user:pass@ip:port} — 账号密码模式</li>
     *   <li>{@code http://user:pass@ip:port} — 账号密码模式</li>
     * </ul>
     *
     * @param proxy 代理字符串(来自 provider)
     * @return 解析结果;解析失败 host=null, port=-1
     */
    private ParsedProxy parseProxy(String proxy) {
        ParsedProxy p = new ParsedProxy();
        p.port = -1;  // 默认失败

        if (proxy == null || proxy.isEmpty()) {
            log.warn("[parseProxy] 代理字符串为空");
            return p;
        }

        String s = proxy.trim();
        log.debug("[parseProxy] 开始解析代理: {}", s);

        // 1. 剥 scheme (http:// 或 https://)
        int schemeIdx = s.indexOf("://");
        if (schemeIdx > 0) {
            String scheme = s.substring(0, schemeIdx);
            s = s.substring(schemeIdx + 3);
            log.debug("[parseProxy] 剥 scheme: scheme={}, 剩余={}", scheme, s);
        }

        // 2. 剥 user:pass@ (有 @ 表示账号密码模式)
        int atIdx = s.lastIndexOf('@');
        if (atIdx > 0) {
            String userinfo = s.substring(0, atIdx);
            s = s.substring(atIdx + 1);
            int colon = userinfo.indexOf(':');
            if (colon > 0) {
                p.user = userinfo.substring(0, colon);
                p.pass = userinfo.substring(colon + 1);
                log.debug("[parseProxy] 剥 user:pass@: user={}, pass=***", p.user);
            } else {
                log.warn("[parseProxy] userinfo 格式异常(无冒号): {}", userinfo);
            }
        } else {
            log.debug("[parseProxy] 无 @, 白名单模式");
        }

        // 3. 解析 host:port
        int colon = s.lastIndexOf(':');
        if (colon > 0) {
            p.host = s.substring(0, colon);
            try {
                p.port = Integer.parseInt(s.substring(colon + 1));
            } catch (NumberFormatException e) {
                log.warn("[parseProxy] 端口解析失败: {}", s.substring(colon + 1));
                p.port = -1;
            }
        } else {
            log.warn("[parseProxy] host:port 格式异常(无冒号): {}", s);
        }

        log.debug("[parseProxy] 解析结果: host={}, port={}, hasAuth={}", p.host, p.port, (p.user != null));
        return p;
    }

    /**
     * 从代理字符串提取可读的代理信息(用于日志,密码脱敏)。
     *
     * @param proxy 代理字符串
     * @return 形如 {@code "ip:port"} 或 {@code "user:***@ip:port"}
     */
    private String extractProxyInfo(String proxy) {
        ParsedProxy p = parseProxy(proxy);
        if (p.host == null) {
            return "(解析失败)";
        }
        if (p.user != null) {
            return p.user + ":***@" + p.host + ":" + p.port;
        }
        return p.host + ":" + p.port;
    }

    // ========================================================================
    // 私有方法:获取代理
    // ========================================================================

    /**
     * 从巨量提取 1 个代理。
     *
     * @return 代理字符串(形如 {@code "http://ip:port"});失败返回 null
     */
    public String acquireProxy() {
        log.debug("[acquireProxy] 开始从巨量获取代理");
        try {
            String proxy = proxyProvider.acquire();
            if (proxy == null) {
                log.warn("[acquireProxy] provider 返回 null");
            } else {
                log.info("[acquireProxy] 获取代理成功: {}", proxy);
            }
            return proxy;
        } catch (Exception e) {
            log.error("[acquireProxy] provider 异常: {}", e.getMessage(), e);
            return null;
        }
    }
}
