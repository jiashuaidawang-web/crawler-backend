package com.dunwugudao.crawler.persistence.mapper;

import com.dunwugudao.crawler.persistence.entity.StockBoardRel;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 板块-个股关联 Mapper（ClickHouse）。
 * <p>主键 (board_code, ts_code, board_type, data_source) —— 多源可能给同一关系，
 * 去重由表引擎 {@code ReplacingMergeTree(_ver=data_source)} 承接（同键保留高 data_source 行）。</p>
 */
@Mapper
public interface StockBoardRelMapper {

    /** 批量追加（多行 VALUES）。 */
    void batchInsert(@Param("list") List<StockBoardRel> rows);
}
