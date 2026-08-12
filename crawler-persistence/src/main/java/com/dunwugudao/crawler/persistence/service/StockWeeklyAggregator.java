package com.dunwugudao.crawler.persistence.service;

import com.dunwugudao.crawler.persistence.entity.StockDaily;
import com.dunwugudao.crawler.persistence.entity.StockWeekly;
import com.dunwugudao.crawler.persistence.mapper.StockDailyMapper;
import com.dunwugudao.crawler.persistence.mapper.StockWeeklyMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 周 K 聚合器(独立业务,不跟 STOCK_DAILY 写在一起)。
 * <p>从日 K 表(FINAL 去重)按周聚合,补全 stock_weekly 的扩展字段:
 * volume_ratio / avg_price / main_net / market_code / pe_static / amplitude / pct_chg / chg_amount。</p>
 *
 * <p>触发时机:周 K 端点任务完成后调用 {@link #aggregateWeeklyForStock(String)}。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class StockWeeklyAggregator {

    private final StockDailyMapper stockDailyMapper;
    private final StockWeeklyMapper stockWeeklyMapper;

    /**
     * 聚合单只股票的所有周(从日 K 表读 FINAL 去重,按周分组,补全 stock_weekly 扩展字段)。
     *
     * @param tsCode 股票代码(如 600000.SH)
     */
    public int aggregateWeeklyForStock(String tsCode) {
        if (tsCode == null || tsCode.isBlank()) return 0;
        // 1. 读日 K 表(按日期升序)
        List<StockDaily> dailyList = stockDailyMapper.selectByTsCode(tsCode);
        if (dailyList == null || dailyList.isEmpty()) {
            log.debug("[aggregateWeeklyForStock] tsCode={} 无日K数据,跳过", tsCode);
            return 0;
        }
        // 2. 按周分组(周起始日=周一)
        Map<LocalDate, List<StockDaily>> weeklyGroups = new HashMap<>();
        for (StockDaily d : dailyList) {
            if (d.getTradeDate() == null) continue;
            LocalDate weekStart = d.getTradeDate().with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
            weeklyGroups.computeIfAbsent(weekStart, k -> new ArrayList<>()).add(d);
        }
        // 3. 每周聚合
        int inserted = 0;
        List<StockWeekly> batch = new ArrayList<>();
        LocalDate currentWeekStart = LocalDate.now().with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        for (var entry : weeklyGroups.entrySet()) {
            LocalDate weekStart = entry.getKey();
            List<StockDaily> weekDays = entry.getValue();
            boolean isCurrentWeek = weekStart.equals(currentWeekStart);
            if (isCurrentWeek) {
                // 本周:覆盖更新(先删后插,因为数据每天都在变化)
                stockWeeklyMapper.deleteByTsCodeAndTradeDate(tsCode, weekStart);
            } else {
                // 历史周(已收盘):有则跳过,无则插入
                if (weekAlreadyExists(tsCode, weekStart)) continue;
            }
            StockWeekly weekly = calculateWeekly(tsCode, weekStart, weekDays);
            if (weekly != null) batch.add(weekly);
        }
        if (!batch.isEmpty()) {
            for (StockWeekly w : batch) {
                stockWeeklyMapper.batchInsert(List.of(w));
                inserted++;
            }
        }
        log.info("[aggregateWeeklyForStock] tsCode={}, 聚合 {} 周(日K {} 行, 本周={})", tsCode, inserted, dailyList.size(), currentWeekStart);
        return inserted;
    }

    /** 检查该周是否已存在(去重)。 */
    private boolean weekAlreadyExists(String tsCode, LocalDate weekStart) {
        List<LocalDate> existing = stockWeeklyMapper.selectExistingTradeDates(tsCode);
        return existing != null && existing.contains(weekStart);
    }

    /** 计算单周的聚合字段。 */
    private StockWeekly calculateWeekly(String tsCode, LocalDate weekStart, List<StockDaily> weekDays) {
        if (weekDays.isEmpty()) return null;
        // 按日期排序
        weekDays.sort(Comparator.comparing(StockDaily::getTradeDate));
        StockWeekly e = new StockWeekly();
        e.setTsCode(tsCode);
        e.setTradeDate(weekStart);
        // 周 open = 周一 open,周 close = 周五 close
        e.setOpen(weekDays.get(0).getOpen());
        e.setClose(weekDays.get(weekDays.size() - 1).getClose());
        // 周 high = max(日内 high),周 low = min(日内 low)
        e.setHigh(weekDays.stream().map(StockDaily::getHigh).filter(v -> v != null).max(Comparator.naturalOrder()).orElse(null));
        e.setLow(weekDays.stream().map(StockDaily::getLow).filter(v -> v != null).min(Comparator.naturalOrder()).orElse(null));
        // 周 vol / amount = sum
        e.setVol(sum(weekDays, StockDaily::getVol));
        e.setAmount(sum(weekDays, StockDaily::getAmount));
        // 周 main_net = sum(日K main_net)
        e.setMainNet(sum(weekDays, StockDaily::getMainNet));
        // 周 market_code = 取任意一日(静态属性)
        e.setMarketCode(weekDays.get(0).getMarketCode());
        // 周 pe_static = 取任意一日(静态属性)
        e.setPeStatic(weekDays.get(0).getPeStatic());
        if (e.getAmount() != null && e.getVol() != null && e.getVol().compareTo(BigDecimal.ZERO) != 0) {
            e.setAvgPrice(e.getAmount().divide(e.getVol(), 4, RoundingMode.HALF_UP));
        }
        // 周振幅 = (周 high - 周 low) / 周 pre_close * 100(用上周收盘做分母,这里用周一的 pre_close)
        BigDecimal preClose = weekDays.get(0).getPreClose();
        if (e.getHigh() != null && e.getLow() != null && preClose != null && preClose.compareTo(BigDecimal.ZERO) != 0) {
            e.setAmplitude(e.getHigh().subtract(e.getLow()).divide(preClose, 4, RoundingMode.HALF_UP).multiply(BigDecimal.valueOf(100)));
        }
        // 周涨跌额 = 周 close - 周 pre_close
        if (e.getClose() != null && preClose != null) {
            e.setChgAmount(e.getClose().subtract(preClose));
        }
        // 量比 = 周 vol / (5 * 日均量) —— 简化:用本周日均量代替
        if (e.getVol() != null && !weekDays.isEmpty()) {
            BigDecimal avgDailyVol = e.getVol().divide(BigDecimal.valueOf(weekDays.size()), 4, RoundingMode.HALF_UP);
            if (avgDailyVol.compareTo(BigDecimal.ZERO) != 0) {
                e.setVolumeRatio(e.getVol().divide(avgDailyVol.multiply(BigDecimal.valueOf(5)), 4, RoundingMode.HALF_UP));
            }
        }
        e.setStockName(weekDays.get(0).getStockName());
        e.setDataSource(weekDays.get(0).getDataSource());
        e.setSrcDetail("aggregated_from_daily");
        e.setCreateDate(LocalDate.now());
        e.setUpdateDate(java.time.LocalDateTime.now());
        return e;
    }

    /** 对 StockDaily 列表求和(忽略 null)。 */
    private BigDecimal sum(List<StockDaily> list, java.util.function.Function<StockDaily, BigDecimal> mapper) {
        return list.stream().map(mapper).filter(v -> v != null).reduce(BigDecimal.ZERO, BigDecimal::add);
    }
}
