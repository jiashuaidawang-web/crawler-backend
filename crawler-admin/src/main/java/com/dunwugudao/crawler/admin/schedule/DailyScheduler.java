package com.dunwugudao.crawler.admin.schedule;

import com.dunwugudao.crawler.admin.pipeline.DailyPipelineOrchestrator;
import com.dunwugudao.crawler.admin.pipeline.PipelineRunResult;
import com.dunwugudao.crawler.admin.pipeline.PipelineStageResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;

/**
 * 每日自动调度器。
 * <ul>
 *   <li>15:30 全量日批编排(含全阶段校验)</li>
 *   <li>18:00 龙虎榜主表(DRAGON_TIGER)独立调度 — 东财此时已发布龙虎榜数据</li>
 *   <li>18:30 龙虎榜明细(DRAGON_TIGER_DETAIL)独立调度 — 依赖 18:00 主表落库</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DailyScheduler {

    private final DailyPipelineOrchestrator pipelineOrchestrator;

    /**
     * 每日 15:30 自动触发日批编排(全阶段)。
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

    /**
     * 每日 18:00 独立调度龙虎榜主表(DRAGON_TIGER)。
     * <p>东财龙虎榜数据通常在 17:00~18:00 发布,15:30 跑批时数据未就绪(空跑浪费 IP)。
     * 此任务单独在 18:00 触发,确保数据源已更新后再抓取。</p>
     */
    @Scheduled(cron = "0 0 18 * * ?")
    public void autoDragonTiger() {
        String date = LocalDate.now().toString();
        log.info("=== 龙虎榜主表独立调度启动(18:00) date={} ===", date);
        try {
            PipelineStageResult result = pipelineOrchestrator.runDragonTigerStage(date);
            log.info("=== 龙虎榜主表调度完成 date={} status={} actual={}/{} ===",
                    date, result.status, result.actualTotal, result.expectedTotal);
        } catch (Exception e) {
            log.error("龙虎榜主表调度失败：{}", e.getMessage(), e);
        }
    }

    /**
     * 每日 18:30 独立调度龙虎榜明细(DRAGON_TIGER_DETAIL)。
     * <p>依赖 18:00 主表调度完成并落库 dragon_tiger 表后,从表读 TRADE_ID 下发明细子任务。</p>
     */
    @Scheduled(cron = "0 30 18 * * ?")
    public void autoDragonTigerDetail() {
        String date = LocalDate.now().toString();
        log.info("=== 龙虎榜明细独立调度启动(18:30) date={} ===", date);
        try {
            PipelineStageResult result = pipelineOrchestrator.runDragonTigerDetailStage(date);
            log.info("=== 龙虎榜明细调度完成 date={} status={} actual={}/{} ===",
                    date, result.status, result.actualTotal, result.expectedTotal);
        } catch (Exception e) {
            log.error("龙虎榜明细调度失败：{}", e.getMessage(), e);
        }
    }
}
