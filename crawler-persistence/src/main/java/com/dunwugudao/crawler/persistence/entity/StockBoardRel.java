package com.dunwugudao.crawler.persistence.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * A7 股票-板块关联（S4 合力结构：一主线多分支、卡位/助攻/中军/后排）。
 * <p>is_leader/is_midarm 是书要的合力结构标记。非接口直接爬取，由 M1 计算或人工标注。</p>
 */
@Data
@TableName("stock_board_rel")
public class StockBoardRel {
    private String tsCode;
    private String boardCode;
    private String boardName;
    private Integer boardType;       // 1：地域 2：行业 3：概念
    private Integer isLeader;        // 是否板块龙头
    private Integer isMidarm;        // 是否中军
    private BigDecimal weight;       // 权重
    private LocalDate effectiveDate; // 生效日
    private Integer source;          // 0=东财 1=同花顺
    private LocalDate createDate;
    private LocalDate updateDate;

}
