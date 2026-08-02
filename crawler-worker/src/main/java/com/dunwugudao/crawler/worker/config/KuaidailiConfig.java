package com.dunwugudao.crawler.worker.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * 快代理（kuaidaili）私密代理配置。
 * <p>对应 application.yml 的 {@code proxy.kuaidaili.*}。密钥外置，避免硬编码进仓库。</p>
 */
@Configuration
@ConfigurationProperties(prefix = "proxy.kuaidaili")
public class KuaidailiConfig {

    /** 快代理 API secret_id。 */
    private String secretId;

    /** 快代理 API signature。 */
    private String signature;

    /** 私密代理账号。 */
    private String username;

    /** 私密代理密码。 */
    private String password;

    public String getSecretId() {
        return secretId;
    }

    public void setSecretId(String secretId) {
        this.secretId = secretId;
    }

    public String getSignature() {
        return signature;
    }

    public void setSignature(String signature) {
        this.signature = signature;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = password;
    }

    /** 配置是否完整（四项皆非空）。 */
    public boolean isConfigured() {
        return secretId != null && !secretId.isBlank()
                && signature != null && !signature.isBlank()
                && username != null && !username.isBlank()
                && password != null && !password.isBlank();
    }
}
