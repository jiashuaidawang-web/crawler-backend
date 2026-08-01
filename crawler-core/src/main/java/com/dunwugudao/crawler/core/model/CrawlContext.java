package com.dunwugudao.crawler.core.model;

import com.dunwugudao.crawler.core.policy.RetryPolicy;
import lombok.Data;

import java.util.Map;

/**
 * 策略执行的上下文：任务 + 策略级配置 + 重试策略。
 * <p>strategyConfig 通常来自 CrawlTask.paramsJson 的反序列化结果（页码/股票/日期区间等）。</p>
 */
@Data
public class CrawlContext {
    private CrawlTask task;
    private Map<String, Object> strategyConfig;
    private RetryPolicy retryPolicy;
}
