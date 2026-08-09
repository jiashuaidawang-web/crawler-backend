package com.dunwugudao.crawler.core.strategy;

import com.dunwugudao.crawler.core.model.SourceType;

import java.util.List;

/**
 * 按 {@link SourceType} + taskType 路由到对应 {@link SourceStrategy}。
 * <p>路由优先级:</p>
 * <ol>
 *   <li>先找同时支持 source + taskType 的策略(精确匹配)</li>
 *   <li>找不到则回退到只匹配 source 的策略(兜底)</li>
 * </ol>
 */
public class StrategyFactory {

    private final List<SourceStrategy> strategies;

    public StrategyFactory(List<SourceStrategy> strategies) {
        this.strategies = strategies;
    }

    public SourceStrategy get(SourceType source) {
        return get(source, null);
    }

    public SourceStrategy get(SourceType source, String taskType) {
        // 1. 精确匹配: source + taskType
        if (taskType != null) {
            for (SourceStrategy s : strategies) {
                if (s.supports(source) && s.supports(taskType)) {
                    return s;
                }
            }
        }
        // 2. 兜底: 只匹配 source
        for (SourceStrategy s : strategies) {
            if (s.supports(source)) {
                return s;
            }
        }
        throw new IllegalStateException("No SourceStrategy supports source: " + source + ", taskType: " + taskType);
    }
}
