package com.dunwugudao.crawler.strategy.eastmoney;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
 * <p>提取 API：{@code https://share.proxy.qg.net/get?key=...&num=1&area=&isp=0&format=json&distinct=true}</p>
 * <ul>
 *   <li>长效 IP：每 30 分钟自动换一次 IP，不限流量</li>
 *   <li>返回 JSON：{@code {"code":"SUCCESS","data":[{"server":"ip:port",...}]}}</li>
 *   <li>认证方式：隧道代理(key 即用户名)</li>
 * </ul>
 */
public class QgLongTermProxyProvider implements ProxyProvider {

    private static final Logger log = LoggerFactory.getLogger(QgLongTermProxyProvider.class);

    private final String apiKey;
    private final String password;

    private final ObjectMapper objectMapper = new ObjectMapper();
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
        this.password = password;
    }

    private String apiUrl() {
        // distinct=false: 相同 key 返回相同 IP(5分钟有效期内保持同一代理)
        return "https://share.proxy.qg.net/get?key=" + apiKey
                + "&num=1&area=&isp=0&format=json&distinct=false";
    }

    /**
     * 从青果长效 IP 提取 1 个代理，返回 {@code http://key:pass@ip:port}。
     *
     * @return 代理字符串；提取失败返回 null
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
            JsonNode root = objectMapper.readTree(text);
            // 成功判定: code == "SUCCESS"
            if (!"SUCCESS".equals(root.path("code").asText())) {
                log.warn("[QgLongTerm] API error: {}", text);
                return null;
            }
            JsonNode data = root.path("data");
            if (!data.isArray() || data.size() == 0) {
                log.warn("[QgLongTerm] data empty: {}", text);
                return null;
            }
            // data[0].server = "ip:port"
            String server = data.get(0).path("server").asText().trim();
            if (server.isEmpty() || !server.contains(":")) {
                log.warn("[QgLongTerm] unexpected server: {}", server);
                return null;
            }
            // "ip:port" → "http://key:pass@ip:port"
            String proxy = "http://" + apiKey + ":" + password + "@" + server;
            log.info("[QgLongTerm] acquired {}", proxy);
            return proxy;
        } catch (IOException e) {
            log.warn("[QgLongTerm] acquire failed: {}", e.getMessage());
            return null;
        }
    }
}
