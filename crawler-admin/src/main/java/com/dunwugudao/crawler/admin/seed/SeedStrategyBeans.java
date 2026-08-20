package com.dunwugudao.crawler.admin.seed;

import com.dunwugudao.crawler.strategy.eastmoney.EastmoneyClient;
import com.dunwugudao.crawler.strategy.eastmoney.ProxyProvider;
import com.dunwugudao.crawler.strategy.eastmoney.QgLongTermProxyProvider;
import com.dunwugudao.crawler.persistence.service.IpConsumptionService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class SeedStrategyBeans {

    @Bean
    public EastmoneyClient eastmoneyClient() {
        return new EastmoneyClient();
    }

    /** 青果长效 IP 提供者。 */
    @Bean
    public ProxyProvider qgProxyProvider() {
        return new QgLongTermProxyProvider("NM8XFDR2", "7pabvimp", "22574E11640D");
    }

    @Bean
    public ProxyManager proxyManager(ProxyProvider qgProxyProvider, IpConsumptionService ipConsumptionService) {
        return new ProxyManager(qgProxyProvider, ipConsumptionService);
    }
}
