package com.dunwugudao.crawler.persistence.mapper;

import com.dunwugudao.crawler.persistence.entity.CrawlAlert;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

@Mapper
public interface CrawlAlertMapper {

    @Select("SELECT * FROM crawl_alert WHERE resolved = #{resolved} ORDER BY created_at DESC")
    List<CrawlAlert> selectByResolved(@Param("resolved") int resolved);

    /** 标记告警处理状态（前端「标记已处理」用）。 */
    @Update("UPDATE crawl_alert SET resolved = #{resolved} WHERE alert_id = #{alertId}")
    int updateResolved(@Param("alertId") Long alertId, @Param("resolved") int resolved);

    /** 插入告警（替代已移除的 BaseMapper.insert）。 */
    @Insert("""
            INSERT INTO crawl_alert
              (alert_type, task_id, task_type, trade_date, source, severity, message,
               value_actual, value_expected, resolved, created_at)
            VALUES
              (#{alertType}, #{taskId}, #{taskType}, #{tradeDate}, #{source}, #{severity}, #{message},
               #{valueActual}, #{valueExpected}, #{resolved}, now())
            """)
    int insert(CrawlAlert alert);
}
