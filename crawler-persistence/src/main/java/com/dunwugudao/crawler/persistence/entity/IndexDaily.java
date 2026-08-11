package com.dunwugudao.crawler.persistence.entity;import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * A1 指数日线（S1 技术面维度 + S4/S6 对比基准）。
 * <p>源自 push2his kline 接口（secid=1.000001 等）。</p>
 */
@Data
public class IndexDaily {
    private LocalDate tradeDate;
    private String indexCode;        // 指数代码(如 000001.SH)
    private String indexName;        // 指数名称
    private Integer secType;         // f1 证券类型: 2=指数
    private BigDecimal open;
    private BigDecimal high;
    private BigDecimal low;
    private BigDecimal close;
    private BigDecimal preClose;
    private BigDecimal pctChg;       // 涨跌幅%
    private BigDecimal changeAmt;    // f4 涨跌额(元)
    private BigDecimal vol;          // 成交量(手)
    private BigDecimal amount;       // 成交额(元)
    private BigDecimal turnover;     // 换手率%
    private Integer dataSource;          // data_source: 0=东财 1=同花顺          // 0=东财 1=同花顺
    private String srcDetail;        // src_detail: 来源URL/接口/备注
    private String dataStatus;       // f152 数据状态: 1盘前/2盘中/3盘后
    private LocalDate createDate;
    private LocalDateTime updateDate;    // 修改日期
}
