package com.dunwugudao.crawler.persistence.entity;import lombok.Data;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Data
public class LimitDownPool {
    private LocalDate tradeDate;
    private String tsCode;
    private String stockName;
    private BigDecimal latestPrice;
    private BigDecimal pctChg;
    private BigDecimal pe;               // 动态市盈率
    private BigDecimal fund;             // 封单资金(元)
    private String lastTime;             // 最后封板 HH:mm:ss
    private BigDecimal fba;              // 板上成交额(元)
    private Integer days;                // 连续跌停天数
    private Integer oc;                  // 开板次数
    private BigDecimal amount;
    private BigDecimal ltsz;
    private BigDecimal tshare;
    private BigDecimal turnoverRate;
    private String boardCode;
    private Integer dataSource;
    private String srcDetail;
    private LocalDate createDate;
    private LocalDateTime updateDate;
}
