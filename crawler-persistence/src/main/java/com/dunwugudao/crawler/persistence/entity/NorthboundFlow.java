package com.dunwugudao.crawler.persistence.entity;

import lombok.Data;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 北向/南向资金日内分钟级数据。
 * <p>direction: 's2n'（沪深→港，南向）或 'n2s'（港→沪深，北向）</p>
 */
@Data
public class NorthboundFlow {
    private LocalDate tradeDate;
    private Integer dataSource;          // data_source: 1=东财
    private String direction;            // 's2n' 或 'n2s'
    private String timePoint;            // 时间点（如 9:30）
    private BigDecimal netInflow;        // 净流入（万元）
    private BigDecimal buyAmount;        // 买入额（万元）
    private BigDecimal sellAmount;       // 卖出额（万元）
    private BigDecimal cumulativeNetInflow; // 累计净流入（万元）
    private BigDecimal statusFlag;       // 状态标记
    private String srcDetail;            // 来源URL
    private LocalDate createDate;
    private LocalDateTime updateDate;
}
