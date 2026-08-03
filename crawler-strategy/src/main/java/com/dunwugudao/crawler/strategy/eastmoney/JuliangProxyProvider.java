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
 * 巨量（juliangip）动态代理提供者。
 *
 * <p>提取 API：{@code http://v2.api.juliangip.com/company/dynamic/getips?auto_white=1&filter=1&num=1&pt=1&result_type=json&trade_no=...&sign=...}</p>
 * <ul>
 *   <li>{@code auto_white=1}：自动把提取请求方 IP 加入白名单（IP 白名单鉴权时代理无需账号密码）</li>
 *   <li>{@code num=1}：一次取 1 个 IP</li>
 *   <li>返回 JSON：{@code data.proxy_list[0]} = "ip:port"}</li>
 * </ul>
 *
 * <p>返回格式：{@code http://user:pass@ip:port}（有用户名密码时）或 {@code http://ip:port}（纯白名单时），
 * 可直接用于 EastmoneyClient / Playwright 代理。</p>
 */
public class JuliangProxyProvider implements ProxyProvider {

    private static final Logger log = LoggerFactory.getLogger(JuliangProxyProvider.class);

    private final String tradeNo;
    private final String sign;
    private final String username;
    private final String password;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final OkHttpClient httpClient = new OkHttpClient.Builder()
            .connectTimeout(Duration.ofSeconds(10))
            .readTimeout(Duration.ofSeconds(15))
            .build();

    /**
     * 白名单鉴权模式（无账号密码）：代理使用 {@code http://ip:port}。
     *
     * @param tradeNo 巨量代理 trade_no
     * @param sign    签名（按巨量文档对请求参数签名）
     */
    public JuliangProxyProvider(String tradeNo, String sign) {
        this(tradeNo, sign, null, null);
    }

    /**
     * 账号密码鉴权模式：代理使用 {@code http://user:pass@ip:port}。
     *
     * @param tradeNo  巨量代理 trade_no
     * @param sign     签名
     * @param username 代理用户名
     * @param password 代理密码
     */
    public JuliangProxyProvider(String tradeNo, String sign, String username, String password) {
        this.tradeNo = tradeNo;
        this.sign = sign;
        this.username = username;
        this.password = password;
    }

    /** 拼提取 URL（trade_no + sign 由调用方预计算好传入）。 */
    private String apiUrl() {
        return "http://v2.api.juliangip.com/company/dynamic/getips?auto_white=1&filter=1&num=1&pt=1&result_type=json"
                + "&trade_no=" + tradeNo + "&sign=" + sign;
    }

    /**
     * 从巨量提取 1 个代理。
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
                log.warn("[Juliang] API HTTP {}", response.code());
                return null;
            }
            ResponseBody body = response.body();
            if (body == null) {
                log.warn("[Juliang] API 返回空 body");
                return null;
            }
            String text = body.string().trim();
            JsonNode root = objectMapper.readTree(text);
            // 成功判定：code == 200
            if (root.path("code").asInt(-1) != 200) {
                log.warn("[Juliang] API business error: code={}, msg={}", root.path("code").asInt(), root.path("msg").asText());
                return null;
            }
            JsonNode list = root.path("data").path("proxy_list");
            if (!list.isArray() || list.size() == 0) {
                log.warn("[Juliang] API proxy_list empty: {}", text);
                return null;
            }
            String ipPort = list.get(0).asText().trim();
            if (ipPort.isEmpty() || !ipPort.contains(":")) {
                log.warn("[Juliang] unexpected proxy: {}", ipPort);
                return null;
            }
            // 有用户名密码 → 带鉴权；否则纯白名单模式
            String proxy = (username != null && !username.isBlank() && password != null)
                    ? "http://" + username + ":" + password + "@" + ipPort
                    : "http://" + ipPort;
            log.info("[Juliang] acquire success: {} (surplus={})", ipPort, root.path("data").path("surplus_quantity").asInt(-1));
            return proxy;
        } catch (IOException e) {
            log.warn("[Juliang] acquire failed: {}", e.getMessage());
            return null;
        }
    }
}
