package com.dunwugudao.crawler.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * crawl_node 表实体（PART D，能力6 节点注册与心跳）。
 * node_id 为手动赋值的字符串主键。
 */
@Data
@TableName("crawl_node")
public class CrawlNode {

    @TableId(type = IdType.INPUT)
    private String nodeId;

    private String nodeName;
    private String ip;
    private String role;
    private String status;
    private LocalDateTime lastHeartbeat;
    private Integer runningTasks;
    private LocalDateTime createdAt;
}
