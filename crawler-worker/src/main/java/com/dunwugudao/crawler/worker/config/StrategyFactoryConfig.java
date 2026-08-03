package com.dunwugudao.crawler.worker.config;

import com.dunwugudao.crawler.core.strategy.StrategyFactory;
import com.dunwugudao.crawler.strategy.eastmoney.EastmoneyApiStrategy;
import com.dunwugudao.crawler.strategy.eastmoney.EastmoneyPlaywrightStrategy;
import com.dunwugudao.crawler.strategy.eastmoney.JuliangProxyProvider;
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
@EnableConfigurationProperties({JuliangConfig.class, KuaidailiConfig.class, QgLongTermConfig.class})
public class StrategyFactoryConfig {

    @Bean
    public BrowserPool browserPool() {
        return new BrowserPool();
    }

    /**
     * Worker 级 IP 管理器 —— OkHttp 路径。
     * <p>优先使用巨量（juliangip）动态代理（{@link JuliangConfig}）；未配置时回退到快代理（{@link KuaidailiConfig}）。
     * 每个 worker 实例一个该 bean，保证各 worker 独立 IP。</p>
     * <p>IP 切换的额度控制已下沉到任务级（{@link EastmoneyApiStrategy#MAX_PROXY_FETCH_ATTEMPTS_PER_TASK}），
     * 一个任务最多用 N 个 IP，接新任务重新计算。这里设为 -1（worker 级无限制），
     * 避免 worker 级熔断与任务级控制冲突——任务级才是“一个任务最多用几个 IP”的真正控制层。</p>
     */
    @Bean
    public WorkerProxyManager eastmoneyOkHttpProxyManager(JuliangConfig juliangConfig, KuaidailiConfig kuaidailiConfig) {
        ProxyProvider provider;
        if (juliangConfig.isConfigured()) {
            provider = juliangConfig.getUsername() != null && !juliangConfig.getUsername().isBlank()
                    ? new JuliangProxyProvider(juliangConfig.getTradeNo(), juliangConfig.getSign(),
                            juliangConfig.getUsername(), juliangConfig.getPassword())
                    : new JuliangProxyProvider(juliangConfig.getTradeNo(), juliangConfig.getSign());
        } else if (kuaidailiConfig.isConfigured()) {
            provider = new KuaidailiProxyProvider(kuaidailiConfig.getSecretId(), kuaidailiConfig.getSignature(),
                    kuaidailiConfig.getUsername(), kuaidailiConfig.getPassword());
        } else {
            provider = new KuaidailiProxyProvider(); // 兜底硬编码（不推荐生产用）
        }
        return new WorkerProxyManager(provider::acquire, -1); // -1 = worker 级无限制，由任务级控制
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
