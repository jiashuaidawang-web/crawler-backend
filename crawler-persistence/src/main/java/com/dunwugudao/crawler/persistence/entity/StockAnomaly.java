package com.dunwugudao.crawler.persistence.entity;

import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 个股异动记录（同花顺）。
 * <p>唯一键 = (ts_code, anomaly_id)，由 ReplacingMergeTree(data_source) 去重。
 * feature 字段存原始 JSON 串。</p>
 */
@Data
public class StockAnomaly {
    private String tsCode;          // 个股代码(带后缀)
    private Long anomalyId;         // 同花顺异动唯一ID（去重键）
    private LocalDate anomalyDate;  // 异动日期
    private String tagCode;         // 异动类型编码
    private String tagName;         // 异动类型中文
    private String reason;          // 异动原因
    private String keywords;        // 关键词JSON数组
    private String stockName;       // 股票名称
    private String feature;         // 原始JSON串
    private Integer dataSource;     // 0=同花顺
    private LocalDate createDate;   // 入库日期
    private LocalDateTime updateDate; // 更新时间
}
