package com.dunwugudao.crawler.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.dunwugudao.crawler.persistence.entity.CrawlAlert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

@Mapper
public interface CrawlAlertMapper extends BaseMapper<CrawlAlert> {

    @Select("SELECT * FROM crawl_alert WHERE resolved = #{resolved} ORDER BY created_at DESC")
    List<CrawlAlert> selectByResolved(@Param("resolved") int resolved);

    /** 标记告警处理状态（前端「标记已处理」用）。 */
    @Update("UPDATE crawl_alert SET resolved = #{resolved} WHERE alert_id = #{alertId}")
    int updateResolved(@Param("alertId") Long alertId, @Param("resolved") int resolved);
}
