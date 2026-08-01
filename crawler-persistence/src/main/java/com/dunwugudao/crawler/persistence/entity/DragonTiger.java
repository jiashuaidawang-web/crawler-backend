package com.dunwugudao.crawler.persistence.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * A8 龙虎榜（S3 主力博弈）。
 * <p>源自 datacenter v1（reportName=RPT_DAILYBILLBOARD_DETAILS）。
 * 实测 30 字段，SECUCODE 已带市场后缀。</p>
 */
@Data
@TableName("dragon_tiger")
public class DragonTiger {
    private LocalDate tradeDate;
    private String tsCode;           // 优先 SECUCODE(带后缀)，否则 SECURITY_CODE
    private String stockName;        // SECURITY_NAME_ABBR
    private String reason;           // EXPLAIN
    private String explanation;      // EXPLANATION
    private String abnormalType;     // CHANGE_TYPE
    private BigDecimal netBuy;       // BILLBOARD_NET_AMT
    private BigDecimal totalBuy;     // BILLBOARD_BUY_AMT
    private BigDecimal totalSell;    // BILLBOARD_SELL_AMT
    // 实测新增字段
    private BigDecimal billboardDealAmt;
    private BigDecimal accumAmount;
    private BigDecimal buyRatio;
    private BigDecimal sellRatio;
    private Integer buySeat;
    private Integer sellSeat;
    private Integer buySeatNew;
    private Integer sellSeatNew;
    private BigDecimal changeRate;
    private BigDecimal closePrice;
    private BigDecimal turnoverrate;
    private BigDecimal freeMarketCap;
    private String market;           // SZ/BJ/SH
    private BigDecimal dealAmountRatio;
    private BigDecimal dealNetRatio;
    private String securityInnerCode;
    private String securityTypeCode;
    private Long tradeId;
    private String tradeMarket;
    private String tradeMarketCode;
    // 基础字段
    private Integer dataSource;          // data_source: 0=东财 1=同花顺
    private LocalDate createDate;
    private LocalDate updateDate;
}
