package com.dunwugudao.crawler.persistence.entity;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 个股分钟K线（量价数据，精确到分钟）。
 * <p>源自 push2his kline 接口（klt=1）。</p>
 */
@Data
public class StockKlineMinute {
    private LocalDate tradeDate;
    private String tsCode;
    private String stockName;
    private LocalDateTime minuteTime;       // 精确到分钟
    private BigDecimal open;
    private BigDecimal high;
    private BigDecimal low;
    private BigDecimal close;
    private BigDecimal vol;        // 成交量(手)
    private BigDecimal amount;     // 成交额(元)
    private BigDecimal amplitude;  // 振幅%
    private BigDecimal pctChg;     // 涨跌幅%
    private BigDecimal turnover;   // 换手率%
    private Integer dataSource;    // 1=东财
    private LocalDate createDate;
}
