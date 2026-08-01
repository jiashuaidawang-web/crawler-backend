package com.dunwugudao.crawler.persistence.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * A6 板块日线（S4 主线强度排序核心）。
 * <p>源自 push2 clist 接口（fs=m:90+t:2 等）。</p>
 */
@Data
@TableName("board_daily")
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
    private Integer dataSource;          // data_source: 0=东财 1=同花顺          // 0=东财 1=同花顺
    private LocalDate createDate;
    private LocalDate updateDate;
}
