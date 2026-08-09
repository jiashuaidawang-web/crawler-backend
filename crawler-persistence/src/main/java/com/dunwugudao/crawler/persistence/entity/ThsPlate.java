package com.dunwugudao.crawler.persistence.entity;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 同花顺板块基础维表（V2 P0-1 THS_PLATE 任务产出）。
 * <p>与 board_basic（东财, board_type=1/2/3）并列, plate_type 用 4/5/6 区分来源。
 * 主键 = (plate_type, plate_code, trade_date), 由 MergeTree ORDER BY 承接去重。</p>
 */
@Data
public class ThsPlate {
    private Long id;
    private Integer plateType;       // 4=地域 5=行业 6=概念（与东财 1/2/3 区分）
    private String plateCode;        // 同花顺板块代码（如 307408）
    private String plateName;        // 板块名称
    private String plateIndex;       // 板块指数代码
    private String leadStockCode;    // 领涨股代码
    private String leadStockName;    // 领涨股名称
    private BigDecimal curPrice;     // 当前价格
    private BigDecimal increase;     // 涨跌幅%
    private BigDecimal turnover;     // 成交额(元)
    private Long volume;             // 成交量(手)
    private Integer upCount;         // 上涨家数
    private Integer downCount;       // 下跌家数
    private LocalDate tradeDate;     // 数据日期
    private Integer dataSource;      // 0=同花顺
    private String srcDetail;        // 溯源 URL
    private LocalDate createDate;
    private LocalDateTime updateDate;
}
