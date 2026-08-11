package com.dunwugudao.crawler.persistence.entity;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * A9 龙虎榜席位明细（S3 破除主力迷信：知名游资≠必胜）。
 * <p>接口：datacenter-web.eastmoney.com/api/data/v1/get?reportName=RPT_BILLBOARD_SEAT。
 * 过滤：filter=(TRADE_ID={tradeId})，按主表 dragon_tiger.TRADE_ID 关联。
 * 实测 31 字段（columns=ALL），OPERATEDEPT_NAME→seat_name, OPERATEDEPT_TYPE→seat_type。</p>
 */
@Data
public class DtDetail {
    private LocalDate tradeDate;
    private String tsCode;
    private String seatName;         // 席位名称
    private String seatType;         // 席位类型(中文描述)
    private Integer rank;            // 排名
    private BigDecimal buy;          // 买入金额
    private BigDecimal sell;         // 卖出金额
    private BigDecimal netBuy;       // 净买入
    private BigDecimal buyRatio;     // 买入占比%
    private BigDecimal sellRatio;    // 卖出占比%
    private BigDecimal netBuyRatio;  // 净买入占比%
    private BigDecimal tradeAmt;     // 成交额
    private BigDecimal tradeRatio;   // 成交额占比%
    private BigDecimal accumVolume;  // 累计成交量(手)
    private BigDecimal accumAmount;  // 累计成交额
    private BigDecimal changeRate;   // 期间涨跌幅%
    private BigDecimal turnoverrateRatio; // 期间换手率%
    private Integer tradeDirection;  // 交易方向
    private Integer statisticsDays;  // 统计天数
    private Integer onlistTimes;     // 上榜次数
    private LocalDate startDate;     // 统计起始日
    private LocalDate endDate;       // 统计截止日
    private String operateDeptCode;  // 席位编号
    private Integer operateDeptType; // 席位类型码
    private String changeType;       // 异常类型码
    private String explanation;      // 上榜原因
    private Long tradeId;            // 关联主表交易ID
    private String securityInnerCode;// 证券内部编码
    private Integer secType;         // 证券类型
    // 基础字段
    private Integer dataSource;      // data_source: 0=东财 1=同花顺
    private String srcDetail;        // src_detail: 来源URL/接口/备注
    private LocalDate createDate;
    private LocalDateTime updateDate;    // 修改日期
}
