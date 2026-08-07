package com.dunwugudao.crawler.strategy.eastmoney;

import okhttp3.Authenticator;
import okhttp3.Credentials;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import okhttp3.Route;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetSocketAddress;
import java.net.Proxy;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 东方财富 HTTP 请求封装（OkHttp）。
 * <p>支持代理（含认证代理 user:pass@ip:port），按代理字符串缓存 OkHttpClient。
 * 非 2xx 或 IO 异常抛 RuntimeException，交由 worker 的 RetryPolicy 裁决。</p>
 *
 * <p><b>关键：东财服务端对 HTTP/2 不兼容（会中途 reset 连接，报 unexpected end of stream），
 * 故强制使用 HTTP/1.1。</b></p>
 */
public class EastmoneyClient {

    private static final Logger log = LoggerFactory.getLogger(EastmoneyClient.class);

    private static final OkHttpClient BASE_CLIENT = new OkHttpClient.Builder()
            .protocols(List.of(Protocol.HTTP_1_1))
            .connectTimeout(Duration.ofSeconds(10))
            .readTimeout(Duration.ofSeconds(30))
            .build();

    private final Map<String, OkHttpClient> proxyClientCache = new ConcurrentHashMap<>();

    /**
     * 执行 GET 请求。
     *
     * @param url 完整 URL
     * @param ua  本次请求的 User-Agent（随机）
     * @param proxy 代理字符串（如 "http://user:pass@ip:port" 或 "ip:port"，可为 null）
     * @return 响应体文本
     */
    public String get(String url, String ua, String proxy) {
        OkHttpClient client = proxy != null && !proxy.isBlank()
                ? proxyClientCache.computeIfAbsent(proxy, EastmoneyClient::buildProxyClient)
                : BASE_CLIENT;

        Request request = new Request.Builder()
                .url(url)
                .header("User-Agent", ua)
                .header("Referer", "https://quote.eastmoney.com/")
                .get()
                .build();

        // DEBUG: 打印代理 IP 和 URL（排查代理/请求问题）
        log.debug("[EastmoneyClient.get] proxy={}, url={}", proxy, url);

        try (Response response = client.newCall(request).execute()) {
            int code = response.code();
            ResponseBody body = response.body();
            String resp = body != null ? body.string() : "";
            // DEBUG: 打印 HTTP 状态码 + 响应体前 500 字符（排查数据截断/异常）
            log.debug("[EastmoneyClient.get] httpCode={}, respLen={}, respPreview={}", code, resp.length(),
                    resp.length() > 500 ? resp.substring(0, 500) + "..." : resp);
            if (!response.isSuccessful()) {
                throw new RuntimeException("Eastmoney HTTP " + code + " for " + url);
            }
            return resp;
        } catch (java.io.IOException e) {
            log.error("[EastmoneyClient.get] IO error, proxy={}, url={}: {}", proxy, url, e.getMessage(), e);
            throw new RuntimeException("Eastmoney request failed: " + e.getMessage(), e);
        }
    }

    /**
     * 为代理字符串构建 OkHttpClient（含认证）。
     * 支持格式：
     *   - "ip:port"
     *   - "user:pass@ip:port"
     *   - "http://user:pass@ip:port"
     *   - "socks5://user:pass@ip:port"
     */
    private static OkHttpClient buildProxyClient(String proxyStr) {
        // 解析协议
        String scheme = "http";
        String rest = proxyStr;
        int idx = proxyStr.indexOf("://");
        if (idx > 0) {
            scheme = proxyStr.substring(0, idx).trim().toLowerCase();
            rest = proxyStr.substring(idx + 3);
        }

        // 解析认证信息
        String username = null;
        String password = null;
        int at = rest.lastIndexOf('@');
        if (at > 0) {
            String auth = rest.substring(0, at);
            rest = rest.substring(at + 1);
            int colon = auth.indexOf(':');
            if (colon > 0) {
                username = auth.substring(0, colon);
                password = auth.substring(colon + 1);
            }
        }

        // 解析 host:port
        int colon = rest.lastIndexOf(':');
        if (colon < 0) {
            throw new IllegalArgumentException("Invalid proxy (expect host:port): " + proxyStr);
        }
        String host = rest.substring(0, colon);
        int port = Integer.parseInt(rest.substring(colon + 1));

        Proxy.Type type = "socks".equals(scheme) || "socks5".equals(scheme)
                ? Proxy.Type.SOCKS : Proxy.Type.HTTP;
        Proxy proxy = new Proxy(type, new InetSocketAddress(host, port));

        OkHttpClient.Builder builder = new OkHttpClient.Builder()
                .protocols(List.of(Protocol.HTTP_1_1))
                .proxy(proxy)
                .connectTimeout(Duration.ofSeconds(15))
                .readTimeout(Duration.ofSeconds(30));

        // 设置代理认证(主动添加 Proxy-Authorization 头,不等 407 触发)
        // 必须用 addNetworkInterceptor: HTTPS 代理 CONNECT 隧道阶段不走应用拦截器,
        // 只有网络拦截器才能给 CONNECT 请求加上 Proxy-Authorization 头,否则会 407 认证失败
        if (username != null && password != null) {
            final String credential = Credentials.basic(username, password);
            builder.addNetworkInterceptor(chain -> {
                Request request = chain.request().newBuilder()
                        .header("Proxy-Authorization", credential)
                        .build();
                return chain.proceed(request);
            });
        }

        return builder.build();
    }
}
