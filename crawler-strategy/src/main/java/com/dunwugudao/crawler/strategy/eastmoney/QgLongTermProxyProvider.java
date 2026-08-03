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
 * 青果长效 IP（住宅隧道）代理提供者。
 *
 * <p>提取 API：{@code https://longterm.proxy.qg.net/get?key=...&num=1&area=&isp=0&format=txt&seq=\r\n&distinct=false}</p>
 * <ul>
 *   <li>长效 IP：每 30 分钟自动换一次 IP，不限流量</li>
 *   <li>返回纯文本：{@code ip:port}（每行一个）</li>
 *   <li>认证方式：用户名密码（隧道代理）</li>
 * </ul>
 *
 * <p>返回格式：{@code http://user:pass@ip:port}，可直接用于 EastmoneyClient / Playwright 代理。</p>
 */
public class QgLongTermProxyProvider implements ProxyProvider {

    private static final Logger log = LoggerFactory.getLogger(QgLongTermProxyProvider.class);

    private final String apiKey;
    private final String username;
    private final String password;

    private final OkHttpClient httpClient = new OkHttpClient.Builder()
            .connectTimeout(Duration.ofSeconds(10))
            .readTimeout(Duration.ofSeconds(15))
            .build();

    /**
     * @param apiKey   提取 API 的 key（也是隧道代理用户名）
     * @param password 隧道代理密码（AuthPwd）
     */
    public QgLongTermProxyProvider(String apiKey, String password) {
        this.apiKey = apiKey;
        this.username = apiKey;  // 用户名 = key
        this.password = password;
    }

    private String apiUrl() {
        return "https://longterm.proxy.qg.net/get?key=" + apiKey
                + "&num=1&area=&isp=0&format=txt&seq=%0D%0A&distinct=false";
    }

    /**
     * 从青果长效 IP 提取 1 个代理，返回 {@code http://user:pass@ip:port}。
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
                log.warn("[QgLongTerm] API HTTP {}", response.code());
                return null;
            }
            ResponseBody body = response.body();
            if (body == null) {
                return null;
            }
            String text = body.string().trim();
            if (text.isEmpty() || !text.contains(":")) {
                log.warn("[QgLongTerm] unexpected response: {}", text);
                return null;
            }
            // 取第一行 "ip:port" → "http://user:pass@ip:port"
            String firstLine = text.split("\\r?\\n")[0].trim();
            String proxy = "http://" + username + ":" + password + "@" + firstLine;
            log.info("[QgLongTerm] acquired {}", firstLine);
            return proxy;
        } catch (IOException e) {
            log.warn("[QgLongTerm] acquire failed: {}", e.getMessage());
            return null;
        }
    }
}
