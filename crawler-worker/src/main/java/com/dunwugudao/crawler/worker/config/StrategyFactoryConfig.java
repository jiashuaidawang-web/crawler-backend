package com.dunwugudao.crawler.worker.config;

import com.dunwugudao.crawler.core.strategy.StrategyFactory;
import com.dunwugudao.crawler.strategy.eastmoney.EastmoneyApiStrategy;
import com.dunwugudao.crawler.strategy.eastmoney.EastmoneyPlaywrightStrategy;
import com.dunwugudao.crawler.strategy.eastmoney.KuaidailiProxyProvider;
import com.dunwugudao.crawler.strategy.eastmoney.ProxyProvider;
import com.dunwugudao.crawler.strategy.eastmoney.QgLongTermProxyProvider;
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
@EnableConfigurationProperties({KuaidailiConfig.class, QgLongTermConfig.class})
public class StrategyFactoryConfig {

    @Bean
    public BrowserPool browserPool() {
        return new BrowserPool();
    }

    /**
     * Worker 级 IP 管理器 —— OkHttp 路径（快代理私密代理）。
     * <p>每个 worker 实例一个该 bean，保证各 worker 独立 IP。</p>
     */
    @Bean
    public WorkerProxyManager eastmoneyOkHttpProxyManager(KuaidailiConfig kuaidailiConfig) {
        ProxyProvider provider = kuaidailiConfig.isConfigured()
                ? new KuaidailiProxyProvider(kuaidailiConfig.getSecretId(), kuaidailiConfig.getSignature(),
                        kuaidailiConfig.getUsername(), kuaidailiConfig.getPassword())
                : new KuaidailiProxyProvider();
        return new WorkerProxyManager(provider::acquire);
    }

    /**
     * Worker 级 IP 管理器 —— Playwright fallback 路径（青果长效 IP）。
     * <p>OkHttp 失败时自动切换到这个代理池。maxProxyFetchAttempts=-1 表示无限制（青果长效 IP 每 30 分钟自动换）。</p>
     */
    @Bean
    public WorkerProxyManager eastmoneyPlaywrightProxyManager(QgLongTermConfig qgConfig) {
        ProxyProvider provider = new QgLongTermProxyProvider(qgConfig.getApiKey(), qgConfig.getPassword());
        return new WorkerProxyManager(provider::acquire, -1);  // -1 = 无限制
    }

    @Bean
    public StrategyFactory strategyFactory(AntiCrawlConfig cfg, BrowserPool pool,
                                           @org.springframework.beans.factory.annotation.Qualifier("eastmoneyOkHttpProxyManager") WorkerProxyManager okHttpProxyManager,
                                           @org.springframework.beans.factory.annotation.Qualifier("eastmoneyPlaywrightProxyManager") WorkerProxyManager playwrightProxyManager) {
        EastmoneyApiStrategy eastmoney = new EastmoneyApiStrategy(cfg);
        eastmoney.setWorkerProxyManager(okHttpProxyManager);
        EastmoneyPlaywrightStrategy playwright = new EastmoneyPlaywrightStrategy(cfg, pool);
        playwright.setWorkerProxyManager(playwrightProxyManager);  // Playwright fallback 用青果长效 IP
        eastmoney.setPlaywrightStrategy(playwright);
        return new StrategyFactory(List.of(
                eastmoney,
                new TonghuashunBrowserStrategy(cfg, pool)));
    }
}
