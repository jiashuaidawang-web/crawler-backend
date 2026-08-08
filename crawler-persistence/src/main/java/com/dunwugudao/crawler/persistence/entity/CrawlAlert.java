package com.dunwugudao.crawler.persistence.entity;import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * crawl_alert 表实体（PART D，能力7 数量校验/异常/反爬/节点掉线告警）。
 */
@Data
public class CrawlAlert {

    private Long alertId;

    private String alertType;
    private Long taskId;
    private String taskType;
    private LocalDate tradeDate;
    private Integer source;
    private String severity;
    private String message;
    private BigDecimal valueActual;
    private BigDecimal valueExpected;
    private Integer resolved;
    private LocalDateTime createdAt;
}
