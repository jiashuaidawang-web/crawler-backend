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
}
