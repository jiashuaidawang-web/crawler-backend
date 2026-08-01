package com.dunwugudao.crawler.persistence.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * A14 财报（S2 两套钱之"企业利润之钱"）。
 * <p>非东财接口，需从财报网站爬取。</p>
 */
@Data
@TableName("financial")
public class Financial {
    private String tsCode;
    private LocalDate endDate;       // 报告期
    private String reportType;       // Q1 / Q2 / Q3 / 年报
    private LocalDate annDate;
    private BigDecimal revenue;      // 营收(元)
    private BigDecimal netProfit;    // 净利润(元)
    private BigDecimal netProfitYoy; // 净利润同比%
    private BigDecimal roe;
    private Integer source;          // 0=东财 1=同花顺
    private LocalDate createDate;
    private LocalDate updateDate;

}
