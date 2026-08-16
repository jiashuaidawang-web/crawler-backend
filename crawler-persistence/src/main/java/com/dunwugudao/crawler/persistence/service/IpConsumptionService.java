package com.dunwugudao.crawler.persistence.service;

import com.dunwugudao.crawler.persistence.mapper.IpConsumptionMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Map;

/** IP 消耗统计服务。admin 探测 + worker 爬取都通过这里记录。 */
@Service
public class IpConsumptionService {

    private final IpConsumptionMapper mapper;

    @Value("${crawler.proxy.agent:unknown}")
    private String agentType;

    public IpConsumptionService(IpConsumptionMapper mapper) {
        this.mapper = mapper;
    }

    /** 记录一次 IP 使用（admin 端：taskId=null；worker 端：taskId=crawl_task.task_id）。 */
    public void log(String consumerType, String stageName, String taskType,
                    Long taskId, String proxyIp, String status,
                    Integer bytes, Long durationMs, String errorMsg, LocalDate tradeDate) {
        try {
            mapper.insert(taskId, consumerType, stageName, taskType, proxyIp, agentType,
                    LocalDateTime.now(), status, bytes, durationMs, errorMsg, tradeDate);
        } catch (Exception e) {
            // 统计失败不影响主流程
        }
    }

    public java.util.Map<String, Object> statsByStage(LocalDate date) {
        return Map.of("stageStats", mapper.statsByStage(date));
    }

    public java.util.Map<String, Object> statsByConsumerType(LocalDate date) {
        return Map.of("consumerStats", mapper.statsByConsumerType(date));
    }

    public java.util.Map<String, Object> statsByAgent(LocalDate date) {
        return Map.of("agentStats", mapper.statsByAgent(date));
    }

    public java.util.Map<String, Object> avgDailyIps(int days) {
        return Map.of("avgStats", mapper.avgDailyIps(LocalDate.now().minusDays(days)));
    }
}
