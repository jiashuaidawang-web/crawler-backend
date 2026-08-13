package com.dunwugudao.crawler.admin.schedule;

import com.dunwugudao.crawler.admin.pipeline.DailyPipelineOrchestrator;
import com.dunwugudao.crawler.admin.pipeline.PipelineRunResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;

/**
 * 每日自动调度器。
 * <p>每天 15:30（收盘后 30 分钟）自动触发日批编排(含全阶段校验)。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DailyScheduler {

    private final DailyPipelineOrchestrator pipelineOrchestrator;

    /**
     * 每日 15:30 自动触发日批编排。
     * cron: 秒 分 时 日 月 周
     */
    @Scheduled(cron = "0 30 15 * * ?")
    public void autoDailySeed() {
        String date = LocalDate.now().toString();
        log.info("=== 自动日批编排启动 date={} ===", date);
        try {
            PipelineRunResult result = pipelineOrchestrator.run(date);
            log.info("=== 日批编排完成 date={} status={} ===", date, result.status());
        } catch (Exception e) {
            log.error("日批编排失败：{}", e.getMessage(), e);
        }
    }
}
