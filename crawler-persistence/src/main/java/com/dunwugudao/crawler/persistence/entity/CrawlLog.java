package com.dunwugudao.crawler.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * crawl_log 表实体（PART D，能力12 全链路日志）。
 */
@Data
@TableName("crawl_log")
public class CrawlLog {

    @TableId(type = IdType.AUTO)
    private Long logId;

    private Long taskId;
    private String node;
    private String url;
    private LocalDateTime startedAt;
    private LocalDateTime finishedAt;
    private Long durationMs;
    private Integer httpStatus;
    /** parser 实际抽出的行数（0=响应空 或 parser 失败，配合 raw 区分）。 */
    private Integer parseRows;
    private String resultStatus;
    /** 响应体文本（最后页），用于排查 parser 问题。 */
    private String raw;
    private Long bytes;
    private String errorMsg;
    private LocalDateTime createdAt;
}
