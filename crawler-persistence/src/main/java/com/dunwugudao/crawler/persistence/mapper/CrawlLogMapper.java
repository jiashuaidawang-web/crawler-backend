package com.dunwugudao.crawler.persistence.mapper;

import com.dunwugudao.crawler.persistence.entity.CrawlLog;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;

/**
 * crawl_log Mapper（openGauss 操作型）。
 * <p>去 BaseMapper 后补回等价原生 SQL 方法（insert）。</p>
 */
@Mapper
public interface CrawlLogMapper {

    @Insert("""
            INSERT INTO crawl_log
              (task_id, node, url, started_at, finished_at, duration_ms, http_status, parse_rows,
               result_status, raw, bytes, error_msg, created_at)
            VALUES
              (#{taskId}, #{node}, #{url}, #{startedAt}, #{finishedAt}, #{durationMs}, #{httpStatus},
               #{parseRows}, #{resultStatus}, #{raw}, #{bytes}, #{errorMsg}, now())
            """)
    int insert(CrawlLog log);
}
