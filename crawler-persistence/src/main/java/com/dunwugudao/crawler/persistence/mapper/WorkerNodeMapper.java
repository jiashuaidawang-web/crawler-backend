package com.dunwugudao.crawler.persistence.mapper;

import com.dunwugudao.crawler.persistence.entity.WorkerNode;
import org.apache.ibatis.annotations.*;

import java.time.LocalDateTime;
import java.util.List;

/**
 * worker_node 表 Mapper。
 * <p>注册、心跳、查询全部用原生 SQL (JdbcTemplate) 亦可，此处提供 MyBatis 接口。</p>
 */
@Mapper
public interface WorkerNodeMapper {

    /**
     * 注册或更新 worker 节点 (openGauss 兼容: 先 UPDATE，影响 0 行则 INSERT)。
     * <p>调用方需先调 updateHeartbeat，返回 0 再调 insert。</p>
     */
    @Insert("INSERT INTO worker_node (worker_id, executor_type, host_name, ip_address, pid, " +
            "capabilities, status, current_jobs, last_heartbeat, started_at) " +
            "VALUES (#{workerId}, #{executorType}, #{hostName}, #{ipAddress}, #{pid}, " +
            "#{capabilities}, #{status}, #{currentJobs}, #{lastHeartbeat}, #{startedAt})")
    int insert(WorkerNode node);

    @Select("SELECT * FROM worker_node WHERE worker_id = #{workerId}")
    WorkerNode selectById(@Param("workerId") String workerId);

    @Select("SELECT * FROM worker_node WHERE status = #{status} ORDER BY last_heartbeat DESC")
    List<WorkerNode> selectByStatus(@Param("status") String status);

    @Select("SELECT * FROM worker_node ORDER BY executor_type, status, last_heartbeat DESC")
    List<WorkerNode> selectAll();

    @Update("UPDATE worker_node SET status=#{status}, current_jobs=#{currentJobs}, " +
            "last_heartbeat=#{lastHeartbeat}, updated_at=CURRENT_TIMESTAMP " +
            "WHERE worker_id=#{workerId}")
    int updateHeartbeat(WorkerNode node);

    @Update("UPDATE worker_node SET status='OFFLINE', updated_at=CURRENT_TIMESTAMP " +
            "WHERE last_heartbeat < #{threshold}")
    int markStaleOffline(@Param("threshold") LocalDateTime threshold);

    @Delete("DELETE FROM worker_node WHERE worker_id = #{workerId}")
    int deleteById(@Param("workerId") String workerId);
}
