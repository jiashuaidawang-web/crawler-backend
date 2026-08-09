package com.dunwugudao.crawler.persistence.entity;

import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 股票日K历史回填进度（断点续传）。
 * <p>对应 openGauss 的 {@code crawl_stock_backfill_status} 表，每只股票一行，
 * 记录该股票日K回填到哪天、是否成功。断点续传时跳过已是 SUCCESS 的股票。</p>
 */
@Data
public class StockBackfillStatus {
    private String tsCode;        // 股票代码 600519.SH（主键）
    private String status;        // PENDING / RUNNING / SUCCESS / FAILED
    private LocalDate earliestDate; // 该股票目前已回填的最早日期
    private LocalDate latestDate; // 该股票目前已回填的最晚日期
    private Integer rowCount;     // 已写入行数
    private String errorMsg;      // 失败原因
    private LocalDateTime updatedAt;
}
