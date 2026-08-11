package com.dunwugudao.crawler.persistence.mapper;

import com.dunwugudao.crawler.persistence.entity.StockTaskConfig;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface StockTaskConfigMapper {

    /** 按类型查启用的股票列表 */
    List<StockTaskConfig> selectByTypeAndStatus(@Param("type") String type, @Param("status") int status);

    /** 批量幂等插入 */
    int batchInsertIfAbsent(@Param("list") List<StockTaskConfig> list);
}
