package com.dunwugudao.crawler.persistence.entity;

import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;

@Data
public class StockTaskConfig {
    private String type;          // 任务类型(如 minute)
    private String code;          // 股票代码(600000.SH)
    private String stockName;     // 股票名称
    private Integer status;       // 1=启用 0=禁用
    private LocalDate createDate;
    private LocalDateTime updateDate;
}
