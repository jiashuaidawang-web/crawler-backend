package com.dunwugudao.crawler.core.strategy;

import com.dunwugudao.crawler.core.model.SourceType;

import java.util.List;

/**
 * 按 {@link SourceType} 路由到对应 {@link SourceStrategy}。
 * <p>由 Spring 注入所有策略实现（List&lt;SourceStrategy&gt;），找不到匹配策略时抛异常。</p>
 */
public class StrategyFactory {

    private final List<SourceStrategy> strategies;

    public StrategyFactory(List<SourceStrategy> strategies) {
        this.strategies = strategies;
    }

    public SourceStrategy get(SourceType source) {
        for (SourceStrategy s : strategies) {
            if (s.supports(source)) {
                return s;
            }
        }
        throw new IllegalStateException("No SourceStrategy supports source: " + source);
    }
}
