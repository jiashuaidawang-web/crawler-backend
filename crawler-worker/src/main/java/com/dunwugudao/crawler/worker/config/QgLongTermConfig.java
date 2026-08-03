package com.dunwugudao.crawler.worker.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 青果长效 IP（住宅隧道）配置。
 * <p>对应 application.yml 的 {@code proxy.qg.*}。密钥外置，避免硬编码进仓库。</p>
 * <p>长效 IP 每 30 分钟自动换一次 IP，不限流量。</p>
 */
@ConfigurationProperties(prefix = "proxy.qg")
public class QgLongTermConfig {

    /** 提取 API 的 key（也是隧道代理用户名）。 */
    private String apiKey;

    /** 隧道代理密码（AuthPwd）。 */
    private String password;

    public String getApiKey() {
        return apiKey;
    }

    public void setApiKey(String apiKey) {
        this.apiKey = apiKey;
    }

    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = password;
    }

    /** 配置是否完整。 */
    public boolean isConfigured() {
        return apiKey != null && !apiKey.isBlank()
                && password != null && !password.isBlank();
    }
}
