package com.dunwugudao.crawler.admin.backfill;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

import java.time.LocalDate;
import java.util.List;

/**
 * A股全历史 stock_daily 回填流程编排（backfill profile 下生效）。
 *
 * <p>执行流程：
 * <ol>
 *   <li>TradingCalendarBuilder: 从 akshare-bridge 拉交易日 → 写 stock_trade_calendar</li>
 *   <li>StockDailyGapDetector: 日历 vs stock_daily → 找出缺失交易日</li>
 *   <li>StockBackfillSeeder: 对全市场股票生成 STOCK_DAILY_HISTORY 任务 → 写 crawl_task (PENDING)</li>
 * </ol>
 *
 * <p><b>为什么用 STOCK_DAILY_HISTORY 而不是 STOCK_DAILY？</b>
 * 东财 push2 clist API（STOCK_DAILY）不支持历史日期，始终返回当前市场快照。
 * push2his kline API（STOCK_DAILY_HISTORY）逐券拿全历史，才能回填历史数据。</p>
 *
 * <p>Worker 集群自动认领 PENDING 任务执行补爬。</p>
 */
@Slf4j
@Configuration
@Profile("backfill")
public class BackfillRunner {

    @Bean
    public CommandLineRunner backfillCommandLineRunner(
            TradingCalendarBuilder calendarBuilder,
            StockDailyGapDetector gapDetector,
            StockBackfillSeeder seeder,
            BackfillConfig config) {
        return args -> {
            log.info("==============================================");
            log.info("  A股全历史 stock_daily 回填启动");
            log.info("  范围: {} ~ {}", config.getStartDate(), config.getEndDate());
            log.info("  数据源: {} (1=东财)", config.getSource());
            log.info("  跳过日历构建: {}", config.isSkipCalendarBuild());
            log.info("  仅检测不发任务: {}", config.isGapOnly());
            log.info("==============================================");

            long startTime = System.currentTimeMillis();

            // 步骤1: 构建交易日历
            if (!config.isSkipCalendarBuild()) {
                log.info("[步骤1/3] 构建交易日历...");
                int days = calendarBuilder.buildRange(config.getStartDate(), config.getEndDate(), config.getSource());
                log.info("[步骤1/3] 日历构建完成: {} 天", days);
            } else {
                log.info("[步骤1/3] 跳过日历构建（已存在）");
            }

            // 步骤2: 差距检测
            log.info("[步骤2/3] 检测 stock_daily 缺失交易日...");
            gapDetector.printCalendarSummary();
            List<LocalDate> missingDays = gapDetector.findMissingTradingDays(config.getStartDate(), config.getEndDate());
            log.info("[步骤2/3] 缺失交易日: {} 个", missingDays.size());

            if (missingDays.isEmpty()) {
                log.info("[完成] 无缺失数据，无需补爬！");
                return;
            }

            // 打印缺失分布
            printMissingDistribution(missingDays);

            // 步骤3: 生成 STOCK_DAILY_HISTORY 回填任务
            if (config.isGapOnly()) {
                log.info("[步骤3/3] gap-only 模式：仅检测不发任务，退出");
                log.info("[提示] 如需发任务，设置 backfill.gap-only=false");
                log.info("[提示] 将按全量股票下发 STOCK_DAILY_HISTORY 任务（逐券拿全历史）");
            } else {
                log.info("[步骤3/3] 生成 STOCK_DAILY_HISTORY 任务（逐券拿全历史）...");
                int tasks = seeder.seedAllStocksHistory(config.getSource());
                log.info("[步骤3/3] 生成任务: {} 个（Worker 将自动认领执行）", tasks);
            }

            long elapsed = System.currentTimeMillis() - startTime;
            log.info("==============================================");
            log.info("  回填流程完成，耗时 {} 秒", elapsed / 1000);
            log.info("==============================================");
        };
    }

    /**
     * 打印缺失日期的分布概况。
     */
    private void printMissingDistribution(List<LocalDate> missingDays) {
        if (missingDays.isEmpty()) return;

        // 按年份分组统计
        java.util.Map<Integer, Integer> byYear = new java.util.TreeMap<>();
        for (LocalDate d : missingDays) {
            byYear.merge(d.getYear(), 1, Integer::sum);
        }

        log.info("缺失日期分布（按年份）:");
        byYear.forEach((year, count) -> log.info("  {} 年: {} 天", year, count));
        log.info("  最早缺失: {}", missingDays.get(0));
        log.info("  最晚缺失: {}", missingDays.get(missingDays.size() - 1));
    }
}
