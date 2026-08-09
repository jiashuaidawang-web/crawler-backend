package com.dunwugudao.crawler.persistence.mapper;

import com.dunwugudao.crawler.persistence.entity.ThsPlate;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDate;
import java.util.List;

/**
 * 同花顺板块基础维表 Mapper（ClickHouse）。
 * <p>主键 (plate_type, plate_code, trade_date) 由 MergeTree ORDER BY 承接去重。</p>
 */
@Mapper
public interface ThsPlateMapper {

    /** 批量追加（多行 VALUES）。 */
    void batchInsert(@Param("list") List<ThsPlate> rows);

    /** 按交易日期查板块列表（验证用）。 */
    List<ThsPlate> selectByTradeDate(@Param("tradeDate") LocalDate tradeDate);

    /** 按交易日期 + 板块类型统计数量（验证用）。 */
    int countByTypeAndDate(@Param("plateType") int plateType, @Param("tradeDate") LocalDate tradeDate);
}
