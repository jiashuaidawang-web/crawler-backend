package com.dunwugudao.crawler.persistence.entity;import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * A11 北向资金（S2/S3 外资供需）。
 * <p>端点未实现，留待 M6 接入。</p>
 */
@Data
public class NorthboundFlow {
    private LocalDate tradeDate;
    private BigDecimal hkHoldNet;    // 北向净买入(元)
    private BigDecimal shNet;        // 沪股通净买入
    private BigDecimal szNet;        // 深股通净买入
    private Integer source;          // 0=东财 1=同花顺
    private LocalDate createDate;
    private LocalDate updateDate;

}
