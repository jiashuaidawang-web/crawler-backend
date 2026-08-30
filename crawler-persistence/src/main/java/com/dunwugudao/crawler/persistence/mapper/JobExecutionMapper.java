package com.dunwugudao.crawler.persistence.mapper;

import com.dunwugudao.crawler.persistence.entity.JobExecution;
import org.apache.ibatis.annotations.*;

import java.time.LocalDate;
import java.util.List;

/**
 * job_execution 表 Mapper。
 * <p>前端概览核心: 按 trade_date 查全部 job 执行状态。</p>
 */
@Mapper
public interface JobExecutionMapper {

    /**
     * 查询某交易日全部 job 执行状态 (前端概览主查询)。
     * <p>LEFT JOIN job_definition 拿 display_name。</p>
     */
    @Select("SELECT je.*, jd.display_name, jd.executor_type, jd.job_category, jd.description " +
            "FROM job_execution je " +
            "JOIN job_definition jd ON je.job_type = jd.job_type " +
            "WHERE je.trade_date = #{tradeDate} " +
            "ORDER BY jd.executor_type, jd.job_category DESC, jd.display_name")
    List<JobExecution> selectByTradeDate(@Param("tradeDate") LocalDate tradeDate);

    @Select("SELECT * FROM job_execution WHERE job_type = #{jobType} ORDER BY trade_date DESC LIMIT 30")
    List<JobExecution> selectRecentByJobType(@Param("jobType") String jobType);

    @Select("SELECT * FROM job_execution WHERE execution_id = #{executionId}")
    JobExecution selectById(@Param("executionId") Long executionId);

    /**
     * 插入或更新 (每天每 job 唯一, openGauss 兼容: 先 UPDATE，影响 0 行则 INSERT)。
     */
    @Insert("INSERT INTO job_execution (job_type, trade_date, worker_id, status, " +
            "started_at, finished_at, duration_ms, rows_affected, error_msg, config_snapshot) " +
            "VALUES (#{jobType}, #{tradeDate}, #{workerId}, #{status}, " +
            "#{startedAt}, #{finishedAt}, #{durationMs}, #{rowsAffected}, #{errorMsg}, #{configSnapshot})")
    int insert(JobExecution exec);

    @Update("UPDATE job_execution SET " +
            "worker_id=#{workerId}, status=#{status}, " +
            "started_at=#{startedAt}, finished_at=#{finishedAt}, " +
            "duration_ms=#{durationMs}, rows_affected=#{rowsAffected}, " +
            "error_msg=#{errorMsg}, config_snapshot=#{configSnapshot}, " +
            "updated_at=CURRENT_TIMESTAMP " +
            "WHERE job_type=#{jobType} AND trade_date=#{tradeDate}")
    int update(JobExecution exec);

    @Update("UPDATE job_execution SET status=#{status}, updated_at=CURRENT_TIMESTAMP " +
            "WHERE execution_id=#{executionId}")
    int updateStatus(@Param("executionId") Long executionId, @Param("status") String status);

    @Update("UPDATE job_execution SET status=#{status}, finished_at=CURRENT_TIMESTAMP, " +
            "duration_ms=#{durationMs}, rows_affected=#{rowsAffected}, error_msg=#{errorMsg}, " +
            "updated_at=CURRENT_TIMESTAMP " +
            "WHERE execution_id=#{executionId}")
    int finish(@Param("executionId") Long executionId,
               @Param("status") String status,
               @Param("durationMs") Long durationMs,
               @Param("rowsAffected") Long rowsAffected,
               @Param("errorMsg") String errorMsg);
}
