package com.dunwugudao.crawler.admin.seed;

import com.dunwugudao.crawler.strategy.eastmoney.EastmoneyClient;
import com.dunwugudao.crawler.strategy.eastmoney.ProxyProvider;
import com.dunwugudao.crawler.strategy.eastmoney.QgLongTermProxyProvider;
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
        return new QgLongTermProxyProvider("1887746F", "4A752C090EE7");
    }

    @Bean
    public ProxyManager proxyManager(ProxyProvider qgProxyProvider) {
        return new ProxyManager(qgProxyProvider);
    }
}
