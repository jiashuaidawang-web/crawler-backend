package com.dunwugudao.crawler.admin.seed;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.apache.http.HttpHost;
import org.apache.http.client.fluent.Executor;
import org.apache.http.client.fluent.Request;

import java.net.URI;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 代理管理器：获取代理 + 构建 Executor + 报告质量 + 失败重试。
 */
@Slf4j
public class ProxyManager {

    private static final String PROXY_POOL_URL = "http://124.223.220.245:8088";
    private static final int MAX_RETRIES = 3;

    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * 执行带代理的 HTTP 请求（自动重试 + 报告质量）。
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
            String supplier = extractSupplier(proxyStr);
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

                // 报告成功
                report(proxyStr, true, latency, supplier);

                // 验证响应不是空的或错误
                if (resp == null || resp.isEmpty() || resp.contains("502") || resp.contains("Bad Gateway")) {
                    log.warn("[ProxyManager] 代理返回错误响应 attempt={}/{} proxy={} latency={}ms resp={}",
                            attempt, MAX_RETRIES, proxyInfo, latency, resp.substring(0, Math.min(200, resp.length())));
                    report(proxyStr, false, latency, supplier);
                    continue;
                }

                log.info("[ProxyManager] 请求成功 proxy={} latency={}ms len={}", proxyInfo, latency, resp.length());
                return resp;
            } catch (Exception e) {
                int latency = (int) (System.currentTimeMillis() - start);
                log.warn("[ProxyManager] 请求失败 attempt={}/{} proxy={} latency={}ms error={}",
                        attempt, MAX_RETRIES, proxyInfo, latency, e.getMessage());

                // 报告失败
                report(proxyStr, false, latency, supplier);
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
     * 从代理池获取一个代理。
     */
    public String acquireProxy() {
        try {
            String resp = Executor.newInstance()
                    .execute(Request.Get(PROXY_POOL_URL + "/proxy/acquire"))
                    .returnContent().asString();
            JsonNode node = objectMapper.readTree(resp);
            String proxy = node.path("proxy").asText(null);
            if (proxy != null && !proxy.isEmpty()) {
                return proxy;
            }
            return null;
        } catch (Exception e) {
            log.warn("获取代理失败：{}", e.getMessage());
            return null;
        }
    }

    /**
     * 报告代理使用结果。
     */
    public void report(String proxy, boolean success, int latencyMs, String supplier) {
        try {
            String json = String.format("{\"proxy\":\"%s\",\"success\":%s,\"latency_ms\":%d,\"supplier\":\"%s\"}",
                    proxy.replace("\"", "\\\""), success, latencyMs, supplier != null ? supplier : "");
            Executor.newInstance()
                    .execute(Request.Post(PROXY_POOL_URL + "/proxy/report")
                            .addHeader("Content-Type", "application/json")
                            .bodyString(json, org.apache.http.entity.ContentType.APPLICATION_JSON));
        } catch (Exception e) {
            // 忽略报告失败
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

    private String extractSupplier(String proxy) {
        // 从代理字符串无法直接获取 supplier，返回 null
        return null;
    }
}
