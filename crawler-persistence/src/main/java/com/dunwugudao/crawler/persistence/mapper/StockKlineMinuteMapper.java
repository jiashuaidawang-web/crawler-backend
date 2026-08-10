package com.dunwugudao.crawler.persistence.mapper;

import com.dunwugudao.crawler.persistence.entity.StockKlineMinute;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 个股分钟K线 Mapper（ClickHouse）。
 */
@Mapper
public interface StockKlineMinuteMapper {

    /** 批量追加（多行 VALUES），由 ClickHouse MergeTree 承接。 */
    void batchInsert(@Param("list") List<StockKlineMinute> rows);
}
