package com.dunwugudao.crawler.admin.seed;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 青果长效 IP（住宅隧道）配置（admin 模块）。
 * <p>对应 application.yml 的 {@code proxy.qg.*}。密钥外置，避免硬编码进仓库。</p>
 * <p>长效 IP 每 30 分钟自动换一次 IP，不限流量。</p>
 */
@ConfigurationProperties(prefix = "proxy.qg")
public class QgLongTermConfig {

    /** 提取 API 的 key(URL 的 key 参数)。 */
    private String authKey;

    /** 业务标识(隧道代理用户名)。 */
    private String businessId;

    /** 隧道代理密码(AuthPwd)。 */
    private String password;

    public String getAuthKey() {
        return authKey;
    }

    public void setAuthKey(String authKey) {
        this.authKey = authKey;
    }

    public String getBusinessId() {
        return businessId;
    }

    public void setBusinessId(String businessId) {
        this.businessId = businessId;
    }

    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = password;
    }

    /** 配置是否完整。 */
    public boolean isConfigured() {
        return authKey != null && !authKey.isBlank()
                && businessId != null && !businessId.isBlank()
                && password != null && !password.isBlank();
    }
}
