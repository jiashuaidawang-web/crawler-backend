package com.dunwugudao.crawler.persistence.mapper;

import com.dunwugudao.crawler.persistence.entity.TradeLog;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * TradeLog Mapper（ClickHouse）。无独立 mapper（访问少），补 batchInsert 批量追加。
 */
@Mapper
public interface TradeLogMapper {

    /** 批量追加（多行 VALUES），单次建议 ≤ 1000 行（由调用方切片）。 */
    void batchInsert(@Param("list") List<TradeLog> rows);
}
