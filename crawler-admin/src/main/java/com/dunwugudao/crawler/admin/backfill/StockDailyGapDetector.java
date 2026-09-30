package com.dunwugudao.crawler.admin.backfill;

import com.dunwugudao.crawler.persistence.mapper.StockTradeCalendarMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * stock_daily 差距检测器。
 * <p>对比交易日历表和 stock_daily 表的 trade_date，找出缺失的交易日。
 * 缺失 = 日历上是交易日 但 stock_daily 无数据。</p>
 */
@Slf4j
@Component
public class StockDailyGapDetector {

    private final StockTradeCalendarMapper calendarMapper;

    /** ClickHouse JdbcTemplate（chJdbcTemplate） */
    private final JdbcTemplate chJdbcTemplate;

    public StockDailyGapDetector(StockTradeCalendarMapper calendarMapper,
                                  @Qualifier("chJdbcTemplate") JdbcTemplate chJdbcTemplate) {
        this.calendarMapper = calendarMapper;
        this.chJdbcTemplate = chJdbcTemplate;
    }

    /**
     * 检测 [from, to] 区间内缺失的交易日。
     * <p>逻辑：日历上的交易日集合 - stock_daily 已有的 trade_date 集合 = 缺失日期。</p>
     *
     * @param from 起始日期（含），null 则从1990-12-19开始
     * @param to   结束日期（含），null 则到今天
     * @return 缺失的交易日列表（升序）
     */
    public List<LocalDate> findMissingTradingDays(LocalDate from, LocalDate to) {
        // 1. 从日历表获取所有交易日
        List<LocalDate> allTradingDays;
        if (from != null && to != null) {
            allTradingDays = calendarMapper.selectTradingDaysBetween(from, to);
        } else {
            allTradingDays = calendarMapper.selectAllTradingDays();
        }
        log.info("[StockDailyGapDetector] 日历交易日数: {}", allTradingDays.size());

        if (allTradingDays.isEmpty()) {
            log.warn("[StockDailyGapDetector] 日历表无数据，请先运行 TradingCalendarBuilder 构建日历");
            return List.of();
        }

        // 2. 从 stock_daily 获取已有数据的 trade_date 集合
        //    ClickHouse 查询：DISTINCT trade_date
        LocalDate actualFrom = from != null ? from : allTradingDays.get(0);
        LocalDate actualTo = to != null ? to : allTradingDays.get(allTradingDays.size() - 1);

        Set<LocalDate> existingDates = new HashSet<>();
        try {
            List<String> dateStrs = chJdbcTemplate.queryForList(
                    "SELECT DISTINCT trade_date FROM stock_daily WHERE trade_date BETWEEN ? AND ?",
                    String.class, actualFrom.toString(), actualTo.toString());
            for (String ds : dateStrs) {
                existingDates.add(LocalDate.parse(ds));
            }
        } catch (Exception e) {
            log.warn("[StockDailyGapDetector] 查询 stock_daily 已有日期失败: {}", e.getMessage());
        }
        log.info("[StockDailyGapDetector] stock_daily 已有交易日数: {}", existingDates.size());

        // 3. 差集 = 缺失
        List<LocalDate> missingDays = new java.util.ArrayList<>();
        for (LocalDate d : allTradingDays) {
            if (!existingDates.contains(d)) {
                missingDays.add(d);
            }
        }

        log.info("[StockDailyGapDetector] 缺失交易日数: {}（{} ~ {}）",
                missingDays.size(), actualFrom, actualTo);
        if (!missingDays.isEmpty()) {
            log.info("[StockDailyGapDetector] 缺失范围: {} ~ {}",
                    missingDays.get(0), missingDays.get(missingDays.size() - 1));
        }

        return missingDays;
    }

    /**
     * 统计日历覆盖情况（不执行差距检测，仅打印概览）。
     */
    public void printCalendarSummary() {
        int calendarCount = calendarMapper.countTradingDays();
        log.info("[StockDailyGapDetector] 日历表交易日总数: {}", calendarCount);

        try {
            Integer chCount = chJdbcTemplate.queryForObject(
                    "SELECT COUNT(DISTINCT trade_date) FROM stock_daily", Integer.class);
            log.info("[StockDailyGapDetector] stock_daily 已有交易日数: {}", chCount);
            log.info("[StockDailyGapDetector] 预估缺失: {} 个交易日",
                    calendarCount - (chCount != null ? chCount : 0));
        } catch (Exception e) {
            log.warn("[StockDailyGapDetector] 查询 stock_daily 统计失败: {}", e.getMessage());
        }
    }
}
