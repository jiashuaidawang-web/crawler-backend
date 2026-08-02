package com.dunwugudao.crawler.persistence.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Data
@TableName("zhaban_pool")
public class ZhabanPool {
    private LocalDate tradeDate;
    private String tsCode;
    private String stockName;
    private BigDecimal latestPrice;
    private BigDecimal pctChg;
    private BigDecimal ztp;              // 涨停价(元)
    private BigDecimal zf;               // 振幅%
    private BigDecimal zs;               // 涨速%（炸板池语义）
    private String openTime;             // 首次封板
    private Integer openTimes;           // 炸板次数
    private BigDecimal amount;
    private BigDecimal ltsz;
    private BigDecimal tshare;
    private BigDecimal turnoverRate;
    private String boardCode;
    private Integer zttjCt;
    private Integer zttjDays;
    private Integer dataSource;
    private String srcDetail;
    private LocalDate createDate;
    private LocalDateTime updateDate;
}
