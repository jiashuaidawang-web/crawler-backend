package com.dunwugudao.crawler.persistence.entity;import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * A6 板块日线（S4 主线强度排序核心）。
 * <p>源自 push2 clist 接口（fs=m:90+t:2 等）。</p>
 */
@Data
public class BoardDaily {
    private LocalDate tradeDate;
    private String boardCode;        // 板块代号
    private String boardName;        // 板块名称
    private Integer boardType;       // 1：地域 2：行业 3：概念
    private BigDecimal pctChg;       // 涨跌幅%
    private BigDecimal amount;       // 成交额(元)
    private Integer upCount;         // 上涨家数
    private Integer downCount;       // 下跌家数
    private Integer limitUpCount;    // 板块内涨停家数
    private String leadingCode;      // 领涨股代码
    private String leadingName;      // 领涨股名称
    // 实测新增字段
    private BigDecimal mainNet;      // f62 主力净流入
    private String boardCode2;       // f140 行业代码
    private Integer dataSource;      // data_source: 0=东财 1=同花顺
    private String srcDetail;        // 溯源详情（接口/种子来源）

    // ---- 行情明细（东财 clist f 码映射）----
    private BigDecimal price;                   // f2  价格（收盘价）
    private BigDecimal riseFall;                // f4  涨跌额
    private BigDecimal volume;                  // f5  成交量（手）
    private BigDecimal amplitude;               // f7  振幅%
    private BigDecimal highPrice;               // f15 最高价格
    private BigDecimal lowPrice;                // f16 最低价格
    private BigDecimal todayOpenPrice;          // f17 今开
    private BigDecimal yesterdayReceivedPrice;  // f18 昨收
    private BigDecimal volumeRatio;             // f10 量比
    private BigDecimal turnoverRatio;           // f8  换手率%
    private BigDecimal totalMarketValue;        // f20 总市值
    private BigDecimal circulationMarketValue;  // f21 流通市值

    private LocalDate createDate;
    private LocalDateTime updateDate;
}
