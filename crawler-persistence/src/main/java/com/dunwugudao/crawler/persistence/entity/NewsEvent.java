package com.dunwugudao.crawler.persistence.entity;import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * A12 新闻/政策/题材事件（S1 政策维 + S7 题材催化）。
 * <p>非东财接口，需从新闻网站爬取。</p>
 */
@Data
public class NewsEvent {
    private Long eventId;
    private LocalDateTime eventTime;
    private String title;
    private String content;
    private String source;
    private String category;         // 政策 / 行业 / 公司 / 题材
    private String relatedBoard;     // 关联板块代码(逗号分隔)
    private String relatedTsCode;    // 关联个股代码(逗号分隔)
    private BigDecimal sentimentScore; // 情感分 -1~1
    private Integer isPolicy;        // 是否政策
    private LocalDate createDate;
    private LocalDateTime updateDate;

}
