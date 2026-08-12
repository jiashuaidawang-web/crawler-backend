package com.dunwugudao.crawler.persistence.mapper;

import com.dunwugudao.crawler.persistence.entity.StockWeekly;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDate;
import java.util.List;

/**
 * StockWeekly Mapper（ClickHouse）。去 BaseMapper，改 batchInsert 批量追加。
 */
@Mapper
public interface StockWeeklyMapper {

    /** 批量追加（多行 VALUES），单次建议 ≤ 1000 行（由调用方切片）。 */
    void batchInsert(@Param("list") List<StockWeekly> rows);

    /** 查询某股票已存在的交易日期集合(供写入前去重,避免重复数据)。 */
    List<LocalDate> selectExistingTradeDates(@Param("tsCode") String tsCode);

    /** 删除单只股票单周数据(供本周覆盖更新:本周数据每天变化,需要先删后插)。 */
    void deleteByTsCodeAndTradeDate(@Param("tsCode") String tsCode, @Param("tradeDate") LocalDate tradeDate);
}
