package com.dunwugudao.crawler.persistence.entity;import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * A2 个股日线（S1/S2/S6 底层；昨日涨停今表现、八大特征、技术面）。
 * <p>源自 push2his kline 接口。实测 clist 32 f 码映射到各列。</p>
 * <p>ClickHouse 版：去掉 @TableName/@TableId（CK 无自增主键），纯 POJO。
 * 写入走 {@link com.dunwugudao.crawler.persistence.service.ClickHouseBatchInserter} 批量追加。</p>
 */
@Data
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
    private BigDecimal superBig;     // f66 超大单净流入
    private BigDecimal bigNet;       // f72 大单净流入
    private BigDecimal midNet;       // f78 中单净流入
    private BigDecimal smallNet;     // f84 小单净流入
    private BigDecimal peStatic;     // f115 静态市盈率
    private String leaderCode;       // f128 领涨股代码
    private String industryCode;     // f140 所属行业代码
    private String conceptCode;      // f141 所属概念代码
    private Integer marketCode;      // f152 市场码(0深/1沪/2京)
    private BigDecimal reservedF24;  // f24 年初至今涨跌幅
    private BigDecimal reservedF25;  // f25 涨停价(分→元)
    private BigDecimal reservedF107; // f107 待确认
    private BigDecimal reservedF136; // f136 炸板次数
    private BigDecimal reservedF173; // f173 涨速%
    // push2 clist 完整投影新增字段（2026-08-02）
    private BigDecimal velocity;        // f11 涨速%
    private BigDecimal turnSpeed;       // f22 涨速(另一口径)
    private Integer isNewHigh;          // f22 是否新高 1/0
    private BigDecimal chg60d;          // f23 60日涨跌幅%
    private BigDecimal sealFund;        // f62 封单资金(元)
    private Integer boardDays;          // f115 连板天数
    private String boardStat;           // f128 涨停统计("3/2"=3天2板)
    private String firstSealTime;       // f140 首次封板 HH:mm:ss
    private String lastSealTime;        // f141 最后封板 HH:mm:ss
    private Integer limitType;          // f152 涨停类型
    // 基础字段
    private Integer dataSource;          // data_source: 0=东财 1=同花顺          // 0=东财 1=同花顺
    private LocalDate createDate;    // 创建日期
    private String srcDetail;        // src_detail: 来源URL/接口/备注
    private LocalDateTime updateDate;    // 修改日期
}
