package com.dunwugudao.crawler.persistence.mapper;

import com.dunwugudao.crawler.persistence.entity.StockTradeCalendar;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDate;
import java.util.List;

/**
 * A股交易日历 Mapper（ClickHouse）。
 * <p>独立于项目现有的 TradeCalendarMapper，专用于全历史数据回填。</p>
 */
@Mapper
public interface StockTradeCalendarMapper {

    /**
     * 查询 [from, to] 区间内所有交易日（升序）。
     * <p>ClickHouse ReplacingMergeTree 需用 FINAL 去重旧版本。</p>
     */
    @Select("SELECT trade_date FROM stock_trade_calendar FINAL " +
            "WHERE trade_date BETWEEN #{from} AND #{to} AND is_trading = 1 " +
            "ORDER BY trade_date ASC")
    List<LocalDate> selectTradingDaysBetween(@Param("from") LocalDate from, @Param("to") LocalDate to);

    /**
     * 查询所有交易日（全量，升序）。
     */
    @Select("SELECT trade_date FROM stock_trade_calendar FINAL " +
            "WHERE is_trading = 1 ORDER BY trade_date ASC")
    List<LocalDate> selectAllTradingDays();

    /**
     * 查询已写入的交易日数量。
     */
    @Select("SELECT COUNT(DISTINCT trade_date) FROM stock_trade_calendar FINAL WHERE is_trading = 1")
    int countTradingDays();

    /**
     * 批量追加日历数据（多行 VALUES）。
     * <p>ClickHouse ReplacingMergeTree 幂等，同 trade_date 重复写入自动保留最新版。</p>
     */
    void batchInsert(@Param("list") List<StockTradeCalendar> list);
}
