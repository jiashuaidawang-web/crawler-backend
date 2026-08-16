package com.dunwugudao.crawler.persistence.entity;import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 板块-个股关联关系（存量快照）。
 * <p>主键 = (board_code, ts_code, board_type, data_source)，由数据库唯一约束兜底防重。
 * effective_date 记录关系建立日期（now()），仅作展示，不参与主键。</p>
 * <p>无 id 列：走自定义 insertOrUpdate / selectDataSource，不走 BaseMapper 的按 id 方法。</p>
 */
@Data
public class StockBoardRel {
    private String tsCode;
    private String boardCode;
    private String boardName;
    private String stockName;        // 股票名称（f14）
    private Integer boardType;       // NOT NULL（1=地域 2=行业 3=概念）
    private Integer isLeader;        // 0/1
    private Integer isMidarm;        // 0/1
    private java.math.BigDecimal weight;
    private LocalDate effectiveDate; // 关系建立日期（now()）
    private Integer dataSource;      // 0=东财 1=同花顺
    private LocalDate tradeDate;     // 新增:哪天入库的
    private String srcDetail;
    private LocalDate createDate;
    private LocalDateTime updateDate;
}
