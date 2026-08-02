package com.dunwugudao.crawler.worker.config;

import com.dunwugudao.crawler.core.strategy.StrategyFactory;
import com.dunwugudao.crawler.strategy.eastmoney.EastmoneyApiStrategy;
import com.dunwugudao.crawler.strategy.eastmoney.KuaidailiProxyProvider;
import com.dunwugudao.crawler.strategy.eastmoney.WorkerProxyManager;
import com.dunwugudao.crawler.strategy.tonghuashun.BrowserPool;
import com.dunwugudao.crawler.strategy.tonghuashun.TonghuashunBrowserStrategy;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/**
 * 装配 StrategyFactory：实例化各 SourceStrategy（strategy 模块不依赖 Spring，此处手动装配），
 * 注入 {@link AntiCrawlConfig}（实现 core 接口）与同花顺用的 {@link BrowserPool}，按 source 路由。
 *
 * <p>东财策略额外注入 {@link WorkerProxyManager}（worker 级 IP 管理）：
 * 1 worker 实例绑定 1 个代理 IP，IP 失败后才提取新 IP，避免囤积浪费。</p>
 */
@Configuration
@EnableConfigurationProperties(KuaidailiConfig.class)
public class StrategyFactoryConfig {

    @Bean
    public BrowserPool browserPool() {
        return new BrowserPool();
    }

    /**
     * Worker 级 IP 管理器（东财专用）。
     * <p>每个 worker 实例一个该 bean，保证各 worker 独立 IP。
     * 供应商通过 {@link KuaidailiProxyProvider} 实现（密钥由 {@link KuaidailiConfig} 注入）。</p>
     */
    @Bean
    public WorkerProxyManager eastmoneyWorkerProxyManager(KuaidailiConfig kuaidailiConfig) {
        KuaidailiProxyProvider provider = kuaidailiConfig.isConfigured()
                ? new KuaidailiProxyProvider(kuaidailiConfig.getSecretId(), kuaidailiConfig.getSignature(),
                        kuaidailiConfig.getUsername(), kuaidailiConfig.getPassword())
                : new KuaidailiProxyProvider();
        return new WorkerProxyManager(provider::acquire);
    }

    @Bean
    public StrategyFactory strategyFactory(AntiCrawlConfig cfg, BrowserPool pool, WorkerProxyManager proxyManager) {
        EastmoneyApiStrategy eastmoney = new EastmoneyApiStrategy(cfg);
        eastmoney.setWorkerProxyManager(proxyManager);
        return new StrategyFactory(List.of(
                eastmoney,
                new TonghuashunBrowserStrategy(cfg, pool)));
    }
}
