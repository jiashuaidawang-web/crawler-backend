package com.dunwugudao.crawler.persistence.entity;import lombok.Data;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Data
public class CixinPool {
    private LocalDate tradeDate;
    private String tsCode;
    private String stockName;
    private BigDecimal latestPrice;
    private BigDecimal pctChg;
    private BigDecimal ztp;              // 涨停价(元)，9999999→NULL
    private Integer ods;                 // 开板几日
    private String od;                   // 开板日期 YYYYMMDD
    private String ipod;                 // 上市日期 YYYYMMDD
    private Integer o;                   // 是否新高（1=是）
    private Integer nh;                  // 新高备用
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
