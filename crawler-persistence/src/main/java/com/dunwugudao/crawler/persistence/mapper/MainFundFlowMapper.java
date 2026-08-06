package com.dunwugudao.crawler.persistence.mapper;

import com.dunwugudao.crawler.persistence.entity.MainFundFlow;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * MainFundFlow Mapper（ClickHouse）。去 BaseMapper，改 batchInsert 批量追加。
 */
@Mapper
public interface MainFundFlowMapper {

    /** 批量追加（多行 VALUES），单次建议 ≤ 1000 行（由调用方切片）。 */
    void batchInsert(@Param("list") List<MainFundFlow> rows);
}
