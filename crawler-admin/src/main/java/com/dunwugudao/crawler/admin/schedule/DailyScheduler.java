package com.dunwugudao.crawler.admin.schedule;

import com.dunwugudao.crawler.admin.seed.SeedGenerator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;

/**
 * 每日自动调度器。
 * <p>每天 15:30（收盘后 30 分钟）自动下发种子任务。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DailyScheduler {

    private final SeedGenerator seedGenerator;

    /**
     * 每日 15:30 自动触发。
     * cron: 秒 分 时 日 月 周
     */
    @Scheduled(cron = "0 30 15 * * ?")
    public void autoDailySeed() {
        String date = LocalDate.now().toString();
        log.info("=== 自动下发种子任务 date={} ===", date);
        try {
            int inserted = seedGenerator.dailySeed(date, 1);
            log.info("=== 种子任务下发完成，插入 {} 条 ===", inserted);
        } catch (Exception e) {
            log.error("自动下发种子任务失败：{}", e.getMessage(), e);
        }
    }
}
