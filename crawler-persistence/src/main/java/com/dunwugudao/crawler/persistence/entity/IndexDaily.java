package com.dunwugudao.crawler.persistence.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * A1 指数日线（S1 技术面维度 + S4/S6 对比基准）。
 * <p>源自 push2his kline 接口（secid=1.000001 等）。</p>
 */
@Data
@TableName("index_daily")
public class IndexDaily {
    private LocalDate tradeDate;
    private String indexCode;        // 指数代码(如 000001.SH)
    private String indexName;        // 指数名称
    private BigDecimal open;
    private BigDecimal high;
    private BigDecimal low;
    private BigDecimal close;
    private BigDecimal preClose;
    private BigDecimal pctChg;       // 涨跌幅%
    private BigDecimal vol;          // 成交量(手)
    private BigDecimal amount;       // 成交额(元)
    private BigDecimal turnover;     // 换手率%
    private Integer dataSource;          // data_source: 0=东财 1=同花顺          // 0=东财 1=同花顺
    private LocalDate createDate;
    private LocalDate updateDate;
}
