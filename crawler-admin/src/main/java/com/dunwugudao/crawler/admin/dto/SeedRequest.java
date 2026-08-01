package com.dunwugudao.crawler.admin.dto;

/**
 * POST /api/crawl/seed 请求体。
 * <p>source 为 data_source 代码（0 同花顺 / 1 东财 / 2 其他）。</p>
 */
public record SeedRequest(
        String taskType,
        Integer source,
        String url,
        String paramsJson,
        String uniqueKey,
        Integer expectedCount,
        Integer priority,
        Integer maxRetry
) {
}
