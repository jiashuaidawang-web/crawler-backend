package com.dunwugudao.crawler.core.strategy;

import com.dunwugudao.crawler.core.model.CrawlContext;
import com.dunwugudao.crawler.core.model.CrawlResult;
import com.dunwugudao.crawler.core.model.SourceType;

/**
 * 数据源策略接口。每个实现负责一种来源（同花顺浏览器 / 东财 API …）的抓取与解析。
 * <p>内部如需分页/滚动/重试，由实现自行管理；失败请抛出 RuntimeException，
 * 交由 worker 的 {@link com.dunwugudao.crawler.core.policy.RetryPolicy} 统一裁决。</p>
 */
public interface SourceStrategy {
    /** 该策略是否支持给定来源。 */
    boolean supports(SourceType source);

    /**
     * 该策略是否支持给定任务类型(可选,用于同一 source 下多种任务类型的路由)。
     * <p>默认返回 false,表示不参与 taskType 路由(只按 source 匹配)。</p>
     */
    default boolean supports(String taskType) {
        return false;
    }

    /** 执行抓取，返回结构化结果。 */
    CrawlResult fetch(CrawlContext ctx);
}
