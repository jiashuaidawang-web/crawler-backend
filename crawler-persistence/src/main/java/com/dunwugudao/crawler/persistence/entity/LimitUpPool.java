package com.dunwugudao.crawler.persistence.entity;import lombok.Data;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Data
public class LimitUpPool {
    private LocalDate tradeDate;
    private String tsCode;
    private String stockName;
    private BigDecimal latestPrice;
    private BigDecimal pctChg;
    private Integer boardPos;
    private Integer isFirst;
    private Integer isContinuous;
    private String limitStyle;
    private String openTime;
    private String lastTime;
    private Integer openTimes;
    private BigDecimal fund;             // 封单资金(元)
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
