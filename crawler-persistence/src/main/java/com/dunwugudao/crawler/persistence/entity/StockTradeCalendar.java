package com.dunwugudao.crawler.persistence.entity;

import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * A股交易日历实体（stock_trade日历表，ClickHouse）。
 * <p>覆盖A股从1990-12-19开市至今的所有交易日，用于全历史数据回填时的差距检测。
 * 与项目现有的 trade_calendar 独立，互不干扰。</p>
 */
@Data
public class StockTradeCalendar {

    /** 交易日期 */
    private LocalDate tradeDate;

    /** 是否为交易日：1=交易日 0=休市 */
    private Integer isTrading;

    /** 数据源：0=akshare 1=手动 */
    private Integer dataSource;

    /** 来源详情 */
    private String srcDetail;

    /** 记录创建日期 */
    private LocalDate createDate;

    /** 记录更新时间（ReplacingMergeTree 版本列） */
    private LocalDateTime updateDate;
}
