package com.dunwugudao.crawler.persistence.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 交易日历（M1 算"昨日/前一交易日"用，避免用自然日减一天踩到休市）。
 * <p>非爬虫灌入，由运营维护或从交易所公告导入。</p>
 */
@Data
@TableName("trade_calendar")
public class TradeCalendar {
    private LocalDate tradeDate;
    private Integer isTrading;  // 1=交易日 0=休市

    // ==================== 基础字段 ====================
    private Integer dataSource;          // data_source: 0=东财 1=同花顺
    private String srcDetail;        // src_detail: 来源URL/接口/备注
    private LocalDate createDate;    // create_date: 创建日期
    private java.sql.Timestamp updateDate;    // update_date: 修改日期
}
