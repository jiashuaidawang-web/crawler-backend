package com.dunwugudao.crawler.persistence.mapper;

import com.dunwugudao.crawler.persistence.entity.TradeCalendar;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDate;
import java.util.List;

/**
 * TradeCalendar Mapper（ClickHouse）。
 * <p>写：batchInsert（ReplacingMergeTree 幂等，同 trade_date 重复 seed 自动保留新版）。
 * 读：前一交易日 / 是否交易日 / 区间交易日——M1 算"昨日/前一交易日"的唯一入口，避免各处重复写 SQL。</p>
 */
@Mapper
public interface TradeCalendarMapper {

    /** 批量追加（多行 VALUES），单次建议 ≤ 1000 行（由调用方切片）。 */
    void batchInsert(@Param("list") List<TradeCalendar> rows);

    /** 严格小于 {@code date} 的最大交易日（前一交易日）。无结果返回 null。 */
    LocalDate findPrevTradingDay(@Param("date") LocalDate date);

    /** {@code date} 当天是否为交易日。 */
    boolean isTradingDay(@Param("date") LocalDate date);

    /** [from, to] 区间内交易日升序列表。 */
    List<LocalDate> selectTradingDaysBetween(@Param("from") LocalDate from, @Param("to") LocalDate to);
}
