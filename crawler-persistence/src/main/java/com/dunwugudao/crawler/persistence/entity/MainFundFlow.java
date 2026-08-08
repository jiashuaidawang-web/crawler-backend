package com.dunwugudao.crawler.persistence.entity;import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * A10 主力资金流（S2/S3 资金供需、合力；个股/板块/指数级）。
 * <p>源自 push2 clist 接口（fs=m:0+t:6 个股 / m:90+t:2 板块）。</p>
 */
@Data
public class MainFundFlow {
    private LocalDate tradeDate;
    private String objType;          // stock / board / index
    private String tsCode;           // 个股级
    private String boardCode;        // 板块级
    private String indexCode;        // 指数级
    private BigDecimal mainNet;      // 主力净流入(元)
    private BigDecimal superBig;     // 超大单净流入
    private BigDecimal bigNet;       // 大单净流入
    private BigDecimal midNet;       // 中单净流入
    private BigDecimal smallNet;     // 小单净流入
    private Integer dataSource;          // data_source: 0=东财 1=同花顺          // 0=东财 1=同花顺
    private String srcDetail;        // src_detail: 来源URL/接口/备注
    private LocalDate createDate;
    private LocalDateTime updateDate;    // 修改日期
}
