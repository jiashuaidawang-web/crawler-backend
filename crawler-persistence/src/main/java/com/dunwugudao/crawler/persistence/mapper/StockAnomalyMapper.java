package com.dunwugudao.crawler.persistence.mapper;

import com.dunwugudao.crawler.persistence.entity.StockAnomaly;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 个股异动 Mapper（ClickHouse）。
 * <p>去重由表引擎 ReplacingMergeTree(data_source) 承接。</p>
 * <p>双数据源下由 DataSourceConfig 显式注册 MapperFactoryBean，不加 @Mapper。</p>
 */
public interface StockAnomalyMapper {

    /** 批量追加。 */
    void batchInsert(@Param("list") List<StockAnomaly> rows);

    /** 查询已存在的 anomaly_id（用于增量去重）。 */
    List<Long> findExistingAnomalyIds(@Param("ids") List<Long> ids);
}
