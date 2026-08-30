package com.dunwugudao.crawler.persistence.entity;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * worker_node 表实体。
 * <p>Worker 节点注册、心跳、能力声明。Python Worker 启动时注册，每 30s 心跳。</p>
 */
@Data
public class WorkerNode {

    /** 节点唯一 ID (hostname:pid 或自定义) */
    private String workerId;

    /** JAVA / PYTHON */
    private String executorType;

    private String hostName;
    private String ipAddress;
    private Integer pid;

    /** JSON 数组: 该 worker 能执行的 job_type 列表 */
    private String capabilities;

    /** ONLINE / OFFLINE / DRAINING */
    private String status;

    /** JSON 数组: 当前正在执行的 job_type */
    private String currentJobs;

    private LocalDateTime lastHeartbeat;
    private LocalDateTime startedAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
