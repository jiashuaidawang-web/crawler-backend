package com.dunwugudao.crawler.persistence.mapper;

import com.dunwugudao.crawler.persistence.entity.JobDefinition;
import org.apache.ibatis.annotations.*;

import java.util.List;

/**
 * job_definition 表 Mapper。
 */
@Mapper
public interface JobDefinitionMapper {

    @Select("SELECT * FROM job_definition WHERE enabled = true ORDER BY executor_type, job_category, display_name")
    List<JobDefinition> selectAllEnabled();

    @Select("SELECT * FROM job_definition WHERE job_type = #{jobType}")
    JobDefinition selectByJobType(@Param("jobType") String jobType);

    @Select("SELECT * FROM job_definition WHERE executor_type = #{executorType} AND enabled = true")
    List<JobDefinition> selectByExecutorType(@Param("executorType") String executorType);

    /**
     * 插入或更新 job 定义 (openGauss 兼容: 先 UPDATE，影响 0 行则 INSERT)。
     */
    @Insert("INSERT INTO job_definition (job_type, display_name, executor_type, job_category, " +
            "description, default_config, schedule_strategy, schedule_cron, market_dependent, auto_start, enabled) " +
            "VALUES (#{jobType}, #{displayName}, #{executorType}, #{jobCategory}, " +
            "#{description}, #{defaultConfig}, #{scheduleStrategy}, #{scheduleCron}, #{marketDependent}, #{autoStart}, #{enabled})")
    int insert(JobDefinition def);

    @Update("UPDATE job_definition SET " +
            "display_name=#{displayName}, executor_type=#{executorType}, " +
            "job_category=#{jobCategory}, description=#{description}, " +
            "default_config=#{defaultConfig}, schedule_strategy=#{scheduleStrategy}, " +
            "schedule_cron=#{scheduleCron}, " +
            "market_dependent=#{marketDependent}, auto_start=#{autoStart}, " +
            "enabled=#{enabled}, updated_at=CURRENT_TIMESTAMP " +
            "WHERE job_type=#{jobType}")
    int update(JobDefinition def);

    @Update("UPDATE job_definition SET enabled=#{enabled}, updated_at=CURRENT_TIMESTAMP " +
            "WHERE job_type=#{jobType}")
    int updateEnabled(@Param("jobType") String jobType, @Param("enabled") Boolean enabled);

    /**
     * 更新调度配置（页面"配置"对话框保存用）。
     */
    @Update("UPDATE job_definition SET " +
            "display_name=#{displayName}, description=#{description}, " +
            "schedule_strategy=#{scheduleStrategy}, schedule_cron=#{scheduleCron}, " +
            "market_dependent=#{marketDependent}, auto_start=#{autoStart}, " +
            "enabled=#{enabled}, updated_at=CURRENT_TIMESTAMP " +
            "WHERE job_type=#{jobType}")
    int updateConfig(JobDefinition def);
}
