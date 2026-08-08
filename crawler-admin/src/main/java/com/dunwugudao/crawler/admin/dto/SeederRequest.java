package com.dunwugudao.crawler.admin.dto;

import java.util.List;

/**
 * 直写型种子（concept/trade_calendar/financial/news_event）通用请求体。
 * <p>source 为 data_source 代码（0 东财 / 1 同花顺）。</p>
 */
public record SeederRequest(
        Integer source,
        List<String> tsCodes,  // financial 指定股票池（可空）
        Integer limit          // news_event 拉取条数（可空）
) {
}
