package com.dunwugudao.crawler.persistence.entity;import lombok.Data;

import java.time.LocalDateTime;

/**
 * crawl_node 表实体（PART D，能力6 节点注册与心跳）。
 * node_id 为手动赋值的字符串主键。
 */
@Data
public class CrawlNode {

    private String nodeId;

    private String nodeName;
    private String ip;
    private String role;
    private String status;
    private LocalDateTime lastHeartbeat;
    private Integer runningTasks;
    private LocalDateTime createdAt;
}
