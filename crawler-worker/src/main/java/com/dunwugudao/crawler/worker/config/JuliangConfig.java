package com.dunwugudao.crawler.worker.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 巨量（juliangip）动态代理配置。
 * <p>对应 application.yml 的 {@code proxy.juliang.*}。trade_no + sign 外置，避免硬编码进仓库。</p>
 */
@ConfigurationProperties(prefix = "proxy.juliang")
public class JuliangConfig {

    /** 巨量代理 trade_no。 */
    private String tradeNo;

    /** 巨量代理签名（对提取请求参数预计算）。 */
    private String sign;

    /** 提取城市(如 北京/上海/广州,可选,空=不限)。 */
    private String city;

    /** 代理用户名（账号密码鉴权时填写；纯白名单鉴权留空）。 */
    private String username;

    /** 代理密码（账号密码鉴权时填写）。 */
    private String password;

    public String getTradeNo() {
        return tradeNo;
    }

    public void setTradeNo(String tradeNo) {
        this.tradeNo = tradeNo;
    }

    public String getSign() {
        return sign;
    }

    public void setSign(String sign) {
        this.sign = sign;
    }

    public String getCity() {
        return city;
    }

    public void setCity(String city) {
        this.city = city;
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

    /** trade_no + sign 完整即视为已配置（username/password/city 可选）。 */
    public boolean isConfigured() {
        return tradeNo != null && !tradeNo.isBlank()
                && sign != null && !sign.isBlank();
    }
}
