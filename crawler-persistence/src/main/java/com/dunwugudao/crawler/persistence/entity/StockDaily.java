package com.dunwugudao.crawler.persistence.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * A2 个股日线（S1/S2/S6 底层；昨日涨停今表现、八大特征、技术面）。
 * <p>源自 push2his kline 接口。实测 clist 32 f 码映射到各列。</p>
 */
@Data
@TableName("stock_daily")
public class StockDaily {
    private LocalDate tradeDate;
    private String tsCode;           // 股票代码(如 600000.SH)
    private String stockName;        // 股票名称
    private BigDecimal open;         // 开盘价
    private BigDecimal high;         // 最高价
    private BigDecimal low;          // 最低价
    private BigDecimal close;        // 收盘价
    private BigDecimal preClose;     // 昨收
    private BigDecimal pctChg;       // 涨跌幅%
    private BigDecimal vol;          // 成交量(手)
    private BigDecimal amount;       // 成交额(元)
    private BigDecimal turnover;     // 换手率%
    private BigDecimal totalMv;      // 总市值(元)
    private BigDecimal circMv;       // 流通市值(元)
    private BigDecimal pe;           // 市盈率(TTM)
    private Integer isLimitUp;       // 是否涨停 1/0
    private Integer isLimitDown;     // 是否跌停 1/0
    // 实测 clist 新增字段
    private BigDecimal chgAmount;    // f4 涨跌额
    private BigDecimal amplitude;    // f7 振幅%
    private BigDecimal volumeRatio;  // f10 量比
    private BigDecimal avgPrice;     // f11 均价
    private BigDecimal mainNet;      // f62 主力净流入
    private BigDecimal peStatic;     // f115 静态市盈率
    private String leaderCode;       // f128 领涨股代码
    private String industryCode;     // f140 所属行业代码
    private String conceptCode;      // f141 所属概念代码
    private Integer marketCode;      // f152 市场码(0深/1沪/2京)
    private BigDecimal reservedF24;  // f24 待确认
    private BigDecimal reservedF25;  // f25 待确认
    private BigDecimal reservedF107; // f107 待确认
    private BigDecimal reservedF136; // f136 待确认
    private BigDecimal reservedF173; // f173 待确认
    // 基础字段
    private Integer dataSource;          // data_source: 0=东财 1=同花顺          // 0=东财 1=同花顺
    private LocalDate createDate;    // 创建日期
    private String srcDetail;        // src_detail: 来源URL/接口/备注
    private LocalDate updateDate;    // 修改日期
}
