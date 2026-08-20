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

    /** 青果长效 IP 提供者(凭证从 proxy.qg.* 配置读取,与 worker 一致)。 */
    @Bean
    public ProxyProvider qgProxyProvider(QgLongTermConfig qgConfig) {
        return new QgLongTermProxyProvider(qgConfig.getAuthKey(), qgConfig.getBusinessId(), qgConfig.getPassword());
    }

    @Bean
    public ProxyManager proxyManager(ProxyProvider qgProxyProvider, IpConsumptionService ipConsumptionService) {
        return new ProxyManager(qgProxyProvider, ipConsumptionService);
    }
}
