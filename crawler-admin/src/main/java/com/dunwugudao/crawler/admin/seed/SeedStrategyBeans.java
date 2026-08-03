package com.dunwugudao.crawler.admin.seed;

import com.dunwugudao.crawler.strategy.eastmoney.EastmoneyClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 装配 admin 模块依赖的 strategy 类。
 *
 * <p>{@code crawler-strategy} 模块刻意不依赖 Spring（pom 无 spring 依赖，类无 @Component），
 * 所以 strategy 里的纯 POJO（{@link EastmoneyClient}、{@link KuaidailiProxyProvider} 等）不会自动被组件扫描发现，
 * 必须由上层消费模块手动声明为 bean。本类即 admin 侧的装配点，与 worker 侧的
 * {@code StrategyFactoryConfig} 职责相同、所处模块不同。</p>
 *
 * <p>{@link SeedGenerator} 构造器注入 {@link ProxyManager} 和 {@link EastmoneyClient}，
 * 这两个 bean 在此集中定义，避免散落的 @Component 污染 spring-free 模块边界。</p>
 */
@Configuration
public class SeedStrategyBeans {

    /** 东财 HTTP 客户端（OkHttp）。无状态，单例即可。 */
    @Bean
    public EastmoneyClient eastmoneyClient() {
        return new EastmoneyClient();
    }

    /** admin 侧代理管理器（获取代理 + 构建 Executor + 失败重试）。 */
    @Bean
    public ProxyManager proxyManager() {
        return new ProxyManager();
    }
}
