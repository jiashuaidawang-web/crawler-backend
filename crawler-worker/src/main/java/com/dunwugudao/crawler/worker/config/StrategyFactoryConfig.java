package com.dunwugudao.crawler.worker.config;

import com.dunwugudao.crawler.core.strategy.StrategyFactory;
import com.dunwugudao.crawler.strategy.eastmoney.EastmoneyApiStrategy;
import com.dunwugudao.crawler.strategy.eastmoney.EastmoneyPlaywrightStrategy;
import com.dunwugudao.crawler.strategy.eastmoney.ProxyProvider;
import com.dunwugudao.crawler.strategy.eastmoney.QgLongTermProxyProvider;
import com.dunwugudao.crawler.strategy.eastmoney.WorkerProxyManager;
import com.dunwugudao.crawler.strategy.tonghuashun.BrowserPool;
import com.dunwugudao.crawler.strategy.tonghuashun.CloakServerProcess;
import com.dunwugudao.crawler.strategy.tonghuashun.ThsPlateCrawler;
import com.dunwugudao.crawler.strategy.tonghuashun.ThsPlateDirectCrawler;
import com.dunwugudao.crawler.strategy.tonghuashun.ThsPlateDirectStrategy;
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
@EnableConfigurationProperties({QgLongTermConfig.class})
public class StrategyFactoryConfig {

    @Bean
    public BrowserPool browserPool() {
        return new BrowserPool();
    }

    /**
     * Worker 级 IP 管理器 —— OkHttp 路径。
     * <p>切到青果长效 IP,与 admin 统一。</p>
     */
    @Bean
    public WorkerProxyManager eastmoneyOkHttpProxyManager(QgLongTermConfig qgConfig) {
        ProxyProvider provider = new QgLongTermProxyProvider(qgConfig.getApiKey(), qgConfig.getPassword());
        return new WorkerProxyManager(provider::acquire, -1);
    }

    @Bean
    public WorkerProxyManager eastmoneyPlaywrightProxyManager(QgLongTermConfig qgConfig) {
        return eastmoneyOkHttpProxyManager(qgConfig);
    }

    /**
     * 青果代理提供者 —— CLOAK 浏览器用(同花顺)。
     * <p>与 eastmoneyOkHttpProxyManager 共享同一份青果配置,但独立实例(各自维护自己的代理 IP)。</p>
     */
    @Bean
    public ProxyProvider cloakProxyProvider(QgLongTermConfig qgConfig) {
        return new QgLongTermProxyProvider(qgConfig.getApiKey(), qgConfig.getPassword());
    }

    @Bean
    public StrategyFactory strategyFactory(AntiCrawlConfig cfg, BrowserPool pool,
                                           @org.springframework.beans.factory.annotation.Qualifier("eastmoneyOkHttpProxyManager") WorkerProxyManager okHttpProxyManager,
                                           @org.springframework.beans.factory.annotation.Qualifier("eastmoneyPlaywrightProxyManager") WorkerProxyManager playwrightProxyManager,
                                           ProxyProvider cloakProxyProvider) {
        EastmoneyApiStrategy eastmoney = new EastmoneyApiStrategy(cfg);
        eastmoney.setWorkerProxyManager(okHttpProxyManager);
        EastmoneyPlaywrightStrategy playwright = new EastmoneyPlaywrightStrategy(cfg, pool);
        playwright.setWorkerProxyManager(playwrightProxyManager);  // Playwright fallback 用青果长效 IP
        eastmoney.setPlaywrightStrategy(playwright);
        // CLOAK 浏览器注入青果代理提供者(动态获取代理 IP)
        CloakServerProcess.setProxyProvider(cloakProxyProvider);
        // 同花顺板块爬虫(手动实例化，crawler-strategy 模块无 Spring 依赖）
        ThsPlateCrawler thsPlateCrawler = new ThsPlateCrawler(pool);
        thsPlateCrawler.setProxyProvider(cloakProxyProvider);
        // 同花顺板块直连策略(Playwright 直连代理,不用 CloakBrowser)
        ThsPlateDirectCrawler directCrawler = new ThsPlateDirectCrawler();
        directCrawler.setProxyProvider(cloakProxyProvider);
        return new StrategyFactory(List.of(
                eastmoney,
                new TonghuashunBrowserStrategy(cfg, pool, thsPlateCrawler),
                new ThsPlateDirectStrategy(directCrawler)));
    }
}
