package com.dunwugudao.crawler.persistence.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 板块基础数据表（存量信息，非接口直接爬取）。
 * <p>用途：标识增量（新增哪些板块），分析当下概念下的股票。</p>
 */
@Data
@TableName("board_basic")
public class BoardBasic {
    private Long id;
    private Integer boardType;       // 1：地域 2：行业 3：概念
    private String code;             // 同花顺板块指数代码
    private String boardCode;        // 板块代号(如 BK0450)
    private String boardName;        // 板块名称
    private String features;         // 备用字段
    private Integer status;          // 1=正常 0=删除
    private Integer dataSource;      // data_source: 0=东财 1=同花顺
    private LocalDate createDate;
    private LocalDateTime updateDate;
}
