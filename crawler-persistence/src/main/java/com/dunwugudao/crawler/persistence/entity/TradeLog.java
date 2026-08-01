package com.dunwugudao.crawler.persistence.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * A15 交易日志（S8 用户自填，不爬）。
 * <p>用户手工录入或导入交割单，做心法量化复盘。source=99。</p>
 */
@Data
@TableName("trade_log")
public class TradeLog {
    private Long id;
    private LocalDate tradeDate;
    private String tsCode;
    private String side;             // buy / sell
    private BigDecimal price;
    private BigDecimal qty;
    private String reason;           // 买入逻辑
    private String emotionTag;       // 执行心态标签
    private String yingDai;          // 买对/买错/未明 三态处置
    private Integer dataSource;      // data_source: 99=用户手工
    private String srcDetail;        // src_detail: 来源URL/接口/备注
    private LocalDate createDate;
    private LocalDate updateDate;

}
