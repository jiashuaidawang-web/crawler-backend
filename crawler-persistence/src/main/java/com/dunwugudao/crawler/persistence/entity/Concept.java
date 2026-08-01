package com.dunwugudao.crawler.persistence.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * A13 题材静态属性（S7 炒作因子：稀缺/想象）。
 * <p>非爬虫灌入，由规则启发式初填。</p>
 */
@Data
@TableName("concept")
public class Concept {
    private String themeCode;
    private String themeName;
    private String themeType;        // 概念 / 行业 / 地域
    private BigDecimal scarcity;     // 稀缺性 0~1
    private BigDecimal imagination;  // 想象空间 0~1
    private Integer source;          // 0=东财 1=同花顺
    private LocalDate createDate;
    private LocalDate updateDate;

}
