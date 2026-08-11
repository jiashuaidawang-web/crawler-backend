package com.dunwugudao.crawler.persistence.mapper;

import com.dunwugudao.crawler.persistence.entity.DragonTiger;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDate;
import java.util.List;

/**
 * DragonTiger Mapper（ClickHouse）。去 BaseMapper，改 batchInsert 批量追加。
 */
@Mapper
public interface DragonTigerMapper {

    /** 批量追加（多行 VALUES），单次建议 ≤ 1000 行（由调用方切片）。 */
    void batchInsert(@Param("list") List<DragonTiger> rows);

    /** 查某交易日所有上榜代码（去重，供 chainDragonTigerDetails 用）。 */
    @Select("SELECT DISTINCT ts_code FROM dragon_tiger WHERE trade_date = #{tradeDate} AND ts_code IS NOT NULL")
    List<String> selectDistinctCodes(@Param("tradeDate") LocalDate tradeDate);

    /** 查某交易日所有上榜 TRADE_ID（去重，供 chainSeatDetails 下发席位明细子任务用）。 */
    @Select("SELECT DISTINCT trade_id FROM dragon_tiger WHERE trade_date = #{tradeDate} AND trade_id IS NOT NULL")
    List<Long> selectDistinctTradeIds(@Param("tradeDate") LocalDate tradeDate);
}
