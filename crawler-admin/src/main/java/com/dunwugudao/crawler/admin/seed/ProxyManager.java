package com.dunwugudao.crawler.admin.seed;

import com.dunwugudao.crawler.strategy.eastmoney.JuliangProxyProvider;
import com.dunwugudao.crawler.strategy.eastmoney.ProxyProvider;
import lombok.extern.slf4j.Slf4j;
import org.apache.http.HttpHost;
import org.apache.http.client.fluent.Executor;
import org.apache.http.client.fluent.Request;

import java.net.URI;

/**
 * 代理管理器：获取代理 + 构建 Executor + 失败重试。
 *
 * <p>代理来源使用巨量（juliangip）动态代理，与 worker 模块统一。</p>
 *
 * <p>本类为 spring-free 的纯 POJO（与 strategy 模块统一风格），Spring bean 由
 * {@link SeedStrategyBeans} 手动装配，不加 @Component。</p>
 */
@Slf4j
public class ProxyManager {

    private static final int MAX_RETRIES = 15;

    private final ProxyProvider proxyProvider = new JuliangProxyProvider("1072663527266511", "79c84c7d9e9c02126871f39872f4624a");

    /**
     * 执行带代理的 HTTP 请求（自动重试）。
     *
     * @param url    请求 URL
     * @return 响应内容，失败返回 null
     */
    public String executeWithRetry(String url) {
        for (int attempt = 1; attempt <= MAX_RETRIES; attempt++) {
            String proxyStr = acquireProxy();
            if (proxyStr == null) {
                log.warn("获取代理失败（尝试 {}/{}）", attempt, MAX_RETRIES);
                continue;
            }

            // 解析代理信息用于日志
            String proxyInfo = extractProxyInfo(proxyStr);
            long start = System.currentTimeMillis();

            try {
                Executor executor = buildExecutor(proxyStr);
                HttpHost proxyHost = parseProxyHost(proxyStr);

                Request request = Request.Get(url)
                        .addHeader("Referer", "https://quote.eastmoney.com/center/")
                        .addHeader("User-Agent", "Mozilla/5.0");
                if (proxyHost != null) {
                    request.viaProxy(proxyHost);
                }

                log.info("[ProxyManager] 请求 attempt={}/{} proxy={} url={}", attempt, MAX_RETRIES, proxyInfo, url);

                String resp = executor.execute(request).returnContent().asString();
                int latency = (int) (System.currentTimeMillis() - start);


                // 验证响应不是空的或错误
                if (resp == null || resp.isEmpty() || resp.contains("502") || resp.contains("Bad Gateway")) {
                    log.warn("[ProxyManager] 代理返回错误响应 attempt={}/{} proxy={} latency={}ms resp={}",
                            attempt, MAX_RETRIES, proxyInfo, latency, resp.substring(0, Math.min(200, resp.length())));
                    continue;
                }

                log.info("[ProxyManager] 请求成功 proxy={} latency={}ms len={}", proxyInfo, latency, resp.length());
                return resp;
            } catch (Exception e) {
                int latency = (int) (System.currentTimeMillis() - start);
                log.warn("[ProxyManager] 请求失败 attempt={}/{} proxy={} latency={}ms error={}",
                        attempt, MAX_RETRIES, proxyInfo, latency, e.getMessage());

            }
        }

        log.warn("[ProxyManager] 请求最终失败（已重试 {} 次）：{}", MAX_RETRIES, url);
        return null;
    }

    /**
     * 从代理字符串提取可读的代理信息（ip:port user:pass）。
     */
    private String extractProxyInfo(String proxy) {
        if (proxy == null) return "null";
        try {
            java.net.URI uri = java.net.URI.create(proxy);
            String host = uri.getHost();
            int port = uri.getPort();
            String userinfo = uri.getUserInfo();
            if (userinfo != null && userinfo.contains(":")) {
                return userinfo + "@" + host + ":" + port;
            }
            return host + ":" + port;
        } catch (Exception e) {
            return proxy;
        }
    }

    /**
     * 从快代理提取 1 个 IP（与 worker 统一代理源）。
     * 替换原实现：不再从 124.223.220.245:8088 取代理。
     */
    public String acquireProxy() {
        try {
            return proxyProvider.acquire();
        } catch (Exception e) {
            log.warn("获取快代理失败：{}", e.getMessage());
            return null;
        }
    }

    /**
     * 构建带代理认证的 Executor。
     */
    private Executor buildExecutor(String proxy) {
        if (proxy == null || proxy.isEmpty()) {
            return Executor.newInstance();
        }
        try {
            URI uri = URI.create(proxy);
            String host = uri.getHost();
            int port = uri.getPort();
            String userinfo = uri.getUserInfo();
            String user = null, pass = null;
            if (userinfo != null && userinfo.contains(":")) {
                String[] parts = userinfo.split(":", 2);
                user = parts[0];
                pass = parts[1];
            }
            HttpHost proxyHost = new HttpHost(host, port);
            Executor executor = Executor.newInstance();
            if (user != null && pass != null) {
                executor.auth(proxyHost, user, pass);
            }
            return executor;
        } catch (Exception e) {
            log.warn("解析代理失败：{}", e.getMessage());
            return Executor.newInstance();
        }
    }

    private HttpHost parseProxyHost(String proxy) {
        if (proxy == null || proxy.isEmpty()) {
            return null;
        }
        try {
            URI uri = URI.create(proxy);
            return new HttpHost(uri.getHost(), uri.getPort());
        } catch (Exception e) {
            return null;
        }
    }

}
