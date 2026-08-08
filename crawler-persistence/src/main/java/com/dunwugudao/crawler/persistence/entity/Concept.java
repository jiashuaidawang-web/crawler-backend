package com.dunwugudao.crawler.persistence.entity;import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * A13 题材静态属性（S7 炒作因子：稀缺/想象）。
 * <p>非爬虫灌入，由规则启发式初填。</p>
 */
@Data
public class Concept {
    private String themeCode;
    private String themeName;
    private String themeType;        // 概念 / 行业 / 地域
    private BigDecimal scarcity;     // 稀缺性 0~1
    private BigDecimal imagination;  // 想象空间 0~1
    private Integer dataSource;      // data_source: 0=东财 1=同花顺
    private String srcDetail;        // src_detail: 来源URL/接口/备注
    private LocalDate createDate;
    private LocalDateTime updateDate;

}
