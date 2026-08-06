package com.dunwugudao.crawler.persistence.entity;import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * A3 个股周线（S6 趋势战法：10/30周均线、RS、RSI）。
 * <p>源自 push2his kline 接口（klt=102）。可由 daily 聚合，也可独立爬。</p>
 */
@Data
public class StockWeekly {
    private LocalDate tradeDate;     // 周末日期
    private String tsCode;
    private String stockName;
    private BigDecimal open;
    private BigDecimal high;
    private BigDecimal low;
    private BigDecimal close;
    private BigDecimal vol;
    private BigDecimal amount;
    // 实测新增字段
    private BigDecimal chgAmount;    // f4 涨跌额
    private BigDecimal amplitude;    // f7 振幅%
    private BigDecimal volumeRatio;  // f10 量比
    private BigDecimal avgPrice;     // f11 均价
    private BigDecimal mainNet;      // f62 主力净流入
    private BigDecimal peStatic;     // f115 静态市盈率
    private String leaderCode;       // f128 领涨股代码
    private String industryCode;     // f140 行业代码
    private String conceptCode;      // f141 概念代码
    private Integer marketCode;      // f152 市场码
    // 基础字段
    private Integer dataSource;      // data_source: 0=东财 1=同花顺
    private LocalDate createDate;    // 创建日期
    private String srcDetail;        // src_detail: 来源URL/接口/备注
    private LocalDateTime updateDate;    // 修改日期
}
