package com.dunwugudao.crawler.admin.seed;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 巨量（juliangip）动态代理配置（admin 模块）。
 * <p>对应 application.yml 的 {@code proxy.juliang.*}。与 worker 模块统一代理源。</p>
 */
@ConfigurationProperties(prefix = "proxy.juliang")
public class JuliangConfig {

    /** 巨量代理 trade_no。 */
    private String tradeNo;

    /** 巨量代理签名。 */
    private String sign;

    /** 提取城市(如 北京,可选,空=不限)。 */
    private String city;

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

    /** trade_no + sign 完整即视为已配置。 */
    public boolean isConfigured() {
        return tradeNo != null && !tradeNo.isBlank()
                && sign != null && !sign.isBlank();
    }
}
