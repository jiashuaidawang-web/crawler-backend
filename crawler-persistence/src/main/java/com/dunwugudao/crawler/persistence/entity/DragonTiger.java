package com.dunwugudao.crawler.persistence.entity;import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * A8 龙虎榜（S3 主力博弈）。
 * <p>源自 datacenter v1（reportName=RPT_DAILYBILLBOARD_DETAILS）。
 * 实测 30 字段，SECUCODE 已带市场后缀。</p>
 */
@Data
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
    // 接口 columns=ALL 暴露的补充字段（榜后涨跌幅 + 买卖额补充口径）
    private BigDecimal netBsAmt;           // NET_BS_AMT          龙虎榜净买卖额(另一口径)
    private BigDecimal sumBuyAmt;          // SUM_BUY_AMT         买入总额(含非龙虎榜部分)
    private BigDecimal sumSellAmt;         // SUM_SELL_AMT        卖出总额
    private BigDecimal d1CloseAdjchrate;   // D1_CLOSE_ADJCHRATE  上榜后1日复权涨跌幅
    private BigDecimal d2CloseAdjchrate;   // D2_CLOSE_ADJCHRATE  上榜后2日复权涨跌幅
    private BigDecimal d5CloseAdjchrate;   // D5_CLOSE_ADJCHRATE  上榜后5日复权涨跌幅
    private BigDecimal d10CloseAdjchrate;  // D10_CLOSE_ADJCHRATE 上榜后10日复权涨跌幅
    private BigDecimal d20CloseAdjchrate;  // D20_CLOSE_ADJCHRATE 上榜后20日复权涨跌幅
    private BigDecimal d30CloseAdjchrate;  // D30_CLOSE_ADJCHRATE 上榜后30日复权涨跌幅
    // 基础字段
    private Integer dataSource;          // data_source: 0=东财 1=同花顺
    private String srcDetail;        // src_detail: 来源URL/接口/备注
    private LocalDate createDate;
    private LocalDateTime updateDate;    // 修改日期
}
