package com.dunwugudao.crawler.persistence.mapper;

import com.dunwugudao.crawler.persistence.entity.CrawlNode;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/**
 * crawl_node Mapper（openGauss 操作型）。
 * <p>去 BaseMapper 后补回等价原生 SQL 方法（selectList / selectById / insert / updateById）。</p>
 */
@Mapper
public interface CrawlNodeMapper {

    @Select("SELECT * FROM crawl_node")
    List<CrawlNode> selectList(@Param("unused") Object unused);

    @Select("SELECT * FROM crawl_node WHERE node_id = #{nodeId}")
    CrawlNode selectById(@Param("nodeId") String nodeId);

    @Insert("""
            INSERT INTO crawl_node
              (node_id, node_name, ip, role, status, last_heartbeat, running_tasks, created_at)
            VALUES
              (#{nodeId}, #{nodeName}, #{ip}, #{role}, #{status}, #{lastHeartbeat}, #{runningTasks}, now())
            """)
    int insert(CrawlNode node);

    @Update("""
            UPDATE crawl_node SET
              node_name = #{nodeName}, ip = #{ip}, role = #{role}, status = #{status},
              last_heartbeat = #{lastHeartbeat}, running_tasks = #{runningTasks}
            WHERE node_id = #{nodeId}
            """)
    int updateById(CrawlNode node);
}
