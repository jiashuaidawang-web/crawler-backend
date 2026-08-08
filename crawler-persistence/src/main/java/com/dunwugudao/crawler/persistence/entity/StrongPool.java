package com.dunwugudao.crawler.persistence.entity;import lombok.Data;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Data
public class StrongPool {
    private LocalDate tradeDate;
    private String tsCode;
    private String stockName;
    private BigDecimal latestPrice;
    private BigDecimal pctChg;
    private BigDecimal ztp;              // 涨停价(元)
    private BigDecimal zs;               // 涨速%（强势池语义）
    private Integer nh;                  // 是否新高（1=是）
    private Integer boardPos;            // 连板数 = cc
    private BigDecimal lb;               // 量比
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
