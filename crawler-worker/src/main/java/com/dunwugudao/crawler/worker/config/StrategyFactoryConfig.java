package com.dunwugudao.crawler.worker.config;

import com.dunwugudao.crawler.core.strategy.StrategyFactory;
import com.dunwugudao.crawler.strategy.eastmoney.EastmoneyApiStrategy;
import com.dunwugudao.crawler.strategy.tonghuashun.BrowserPool;
import com.dunwugudao.crawler.strategy.tonghuashun.TonghuashunBrowserStrategy;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/**
 * 装配 StrategyFactory：实例化各 SourceStrategy（strategy 模块不依赖 Spring，此处手动装配），
 * 注入 {@link AntiCrawlConfig}（实现 core 接口）与同花顺用的 {@link BrowserPool}，按 source 路由。
 */
@Configuration
public class StrategyFactoryConfig {

    @Bean
    public BrowserPool browserPool() {
        return new BrowserPool();
    }

    @Bean
    public StrategyFactory strategyFactory(AntiCrawlConfig cfg, BrowserPool pool) {
        return new StrategyFactory(List.of(
                new EastmoneyApiStrategy(cfg),
                new TonghuashunBrowserStrategy(cfg, pool)));
    }
}
