package com.dunwugudao.crawler.strategy.eastmoney;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.time.Duration;

/**
 * 快代理私密代理提供者（OkHttp3 实现）。
 *
 * <p>提取 API：{@code https://dps.kdlapi.com/api/getdps/?secret_id=...&signature=...&num=1&format=text&sep=1}</p>
 * <ul>
 *   <li>{@code num=1}：一次取 1 个 IP（按需取，不囤积，避免浪费）</li>
 *   <li>{@code format=text}：返回纯文本 {@code ip:port}</li>
 *   <li>认证方式：用户名密码（私密代理）</li>
 * </ul>
 *
 * <p>返回格式：{@code http://user:pass@ip:port}，可直接用于 EastmoneyClient 的 OkHttp 代理。</p>
 *
 * <p>密钥通过构造器注入（{@code proxy.kuaidaili.*} 配置）；未注入时使用默认硬编码值，
 * 生产环境请务必注入，不要依赖默认值。</p>
 */
public class KuaidailiProxyProvider implements ProxyProvider {

    private static final Logger log = LoggerFactory.getLogger(KuaidailiProxyProvider.class);

    private final String secretId;
    private final String signature;
    private final String username;
    private final String password;

    // 兜底硬编码（未注入配置时使用）。生产务必通过构造器注入，不要依赖这些默认值。
    private static final String DEFAULT_SECRET_ID = "o3icyopd76ugn5hcya8g";
    private static final String DEFAULT_SIGNATURE = "7dz6v5e9ba1bkejun2q9eobgdhrovfjm";
    private static final String DEFAULT_USERNAME = "d2383368918";
    private static final String DEFAULT_PASSWORD = "tft4pvct";

    private final OkHttpClient httpClient = new OkHttpClient.Builder()
            .connectTimeout(Duration.ofSeconds(10))
            .readTimeout(Duration.ofSeconds(15))
            .build();

    /** 默认构造器（兜底硬编码，兼容未注入配置的场景）。 */
    public KuaidailiProxyProvider() {
        this(DEFAULT_SECRET_ID, DEFAULT_SIGNATURE, DEFAULT_USERNAME, DEFAULT_PASSWORD);
    }

    /** 由配置注入（生产用法）。 */
    public KuaidailiProxyProvider(String secretId, String signature, String username, String password) {
        this.secretId = secretId;
        this.signature = signature;
        this.username = username;
        this.password = password;
    }

    /** 按实例字段拼 URL（URL 非常量，因为密钥来自注入）。 */
    private String apiUrl() {
        return "https://dps.kdlapi.com/api/getdps/?secret_id=" + secretId
                + "&signature=" + signature
                + "&num=1&format=text&sep=1";
    }

    /**
     * 从快代理提取 1 个 IP，返回 {@code http://user:pass@ip:port}。
     *
     * @return 代理字符串；提取失败返回 null（WorkerProxyManager 会稍后重试）
     */
    @Override
    public String acquire() {
        Request request = new Request.Builder()
                .url(apiUrl())
                .get()
                .build();
        try (Response response = httpClient.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                log.warn("[Kuaidaili] API HTTP {} (提取失败，返回 null)", response.code());
                return null;
            }
            ResponseBody body = response.body();
            if (body == null) {
                log.warn("[Kuaidaili] API 返回空 body（提取失败，返回 null）");
                return null;
            }
            String text = body.string().trim();
            if (text.isEmpty() || !text.contains(":")) {
                log.warn("[Kuaidaili] unexpected response: {}（提取失败，返回 null）", text);
                return null;
            }
            // text = "ip:port" → "http://user:pass@ip:port"
            String proxy = "http://" + username + ":" + password + "@" + text;
            log.info("[Kuaidaili] acquire success: {} (proxy={})", text, proxy);
            return proxy;
        } catch (IOException e) {
            log.warn("[Kuaidaili] acquire failed: {}（提取失败，返回 null）", e.getMessage());
            return null;
        }
    }
}
