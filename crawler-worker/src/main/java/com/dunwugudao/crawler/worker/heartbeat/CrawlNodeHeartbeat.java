package com.dunwugudao.crawler.worker.heartbeat;

import com.dunwugudao.crawler.persistence.entity.CrawlNode;
import com.dunwugudao.crawler.persistence.mapper.CrawlNodeMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/**
 * 节点心跳（能力6）：每 30s upsert crawl_node，供监控按节点聚合进度/成功率。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CrawlNodeHeartbeat {

    private final CrawlNodeMapper crawlNodeMapper;

    @Value("${crawler.node-id:${HOSTNAME:worker-node}}")
    private String nodeId;

    @Value("${crawler.node-name:Worker}")
    private String nodeName;

    @Value("${crawler.role:MIXED}")
    private String role;

    @Value("${crawler.ip:127.0.0.1}")
    private String ip;

    @Scheduled(fixedDelay = 30_000)
    public void heartbeat() {
        CrawlNode node = new CrawlNode();
        node.setNodeId(nodeId);
        node.setNodeName(nodeName);
        node.setIp(ip);
        node.setRole(role);
        node.setStatus("UP");
        node.setLastHeartbeat(LocalDateTime.now());

        CrawlNode existing = crawlNodeMapper.selectById(nodeId);
        if (existing == null) {
            node.setCreatedAt(LocalDateTime.now());
            crawlNodeMapper.insert(node);
            log.info("register node {}", nodeId);
        } else {
            crawlNodeMapper.updateById(node);
        }
    }
}
