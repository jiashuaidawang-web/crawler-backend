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
        Integer maxRetry,
        String tradeDate,  // STOCK_DAILY 专用（如 "2026-08-01"），可为 null
        String from,       // TRADE_CALENDAR 区间起（如 "2020-01-01"），可为 null
        String to          // TRADE_CALENDAR 区间止（如 "2030-12-31"），可为 null
) {
}
