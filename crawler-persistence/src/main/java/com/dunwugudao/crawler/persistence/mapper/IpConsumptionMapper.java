package com.dunwugudao.crawler.persistence.mapper;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/** IP 消耗统计 Mapper。 */
public interface IpConsumptionMapper {

    @Insert("""
            INSERT INTO crawl_ip_consumption
            (task_id, consumer_type, stage_name, task_type, proxy_ip, agent_type,
             request_time, response_status, response_bytes, duration_ms, error_msg, trade_date)
            VALUES (#{taskId}, #{consumerType}, #{stageName}, #{taskType}, #{proxyIp}, #{agentType},
                    #{requestTime}, #{responseStatus}, #{responseBytes}, #{durationMs}, #{errorMsg}, #{tradeDate})
            """)
    int insert(@Param("taskId") Long taskId,
               @Param("consumerType") String consumerType,
               @Param("stageName") String stageName,
               @Param("taskType") String taskType,
               @Param("proxyIp") String proxyIp,
               @Param("agentType") String agentType,
               @Param("requestTime") java.time.LocalDateTime requestTime,
               @Param("responseStatus") String responseStatus,
               @Param("responseBytes") Integer responseBytes,
               @Param("durationMs") Long durationMs,
               @Param("errorMsg") String errorMsg,
               @Param("tradeDate") LocalDate tradeDate);

    /** 按业务+日期统计 IP 消耗。 */
    @Select("""
            SELECT stage_name AS stageName,
                   COUNT(DISTINCT proxy_ip) AS ipsUsed,
                   COUNT(*) AS totalRequests,
                   SUM(CASE WHEN response_status = 'SUCCESS' THEN 1 ELSE 0 END) AS successCount,
                   SUM(CASE WHEN response_status != 'SUCCESS' THEN 1 ELSE 0 END) AS failCount
            FROM crawl_ip_consumption
            WHERE trade_date = #{date}
            GROUP BY stage_name
            ORDER BY ipsUsed DESC
            """)
    List<Map<String, Object>> statsByStage(@Param("date") LocalDate date);

    /** 按 consumer_type+日期统计。 */
    @Select("""
            SELECT consumer_type AS consumerType,
                   COUNT(DISTINCT proxy_ip) AS ipsUsed,
                   COUNT(*) AS totalRequests,
                   SUM(CASE WHEN response_status = 'SUCCESS' THEN 1 ELSE 0 END) AS successCount,
                   SUM(CASE WHEN response_status != 'SUCCESS' THEN 1 ELSE 0 END) AS failCount
            FROM crawl_ip_consumption
            WHERE trade_date = #{date}
            GROUP BY consumer_type
            """)
    List<Map<String, Object>> statsByConsumerType(@Param("date") LocalDate date);

    /** 按代理商+日期统计。 */
    @Select("""
            SELECT agent_type AS agentType,
                   COUNT(DISTINCT proxy_ip) AS ipsUsed,
                   COUNT(*) AS totalRequests,
                   SUM(CASE WHEN response_status = 'SUCCESS' THEN 1 ELSE 0 END) AS successCount,
                   SUM(CASE WHEN response_status != 'SUCCESS' THEN 1 ELSE 0 END) AS failCount
            FROM crawl_ip_consumption
            WHERE trade_date = #{date}
            GROUP BY agent_type
            """)
    List<Map<String, Object>> statsByAgent(@Param("date") LocalDate date);

    /** 近 N 天平均 IP 消耗（采购预测）。 */
    @Select("""
            SELECT stage_name AS stageName,
                   consumer_type AS consumerType,
                   ROUND(AVG(daily_ips), 0) AS avgDailyIps,
                   MAX(daily_ips) AS peakDailyIps
            FROM (
                SELECT stage_name, consumer_type, trade_date, COUNT(DISTINCT proxy_ip) AS daily_ips
                FROM crawl_ip_consumption
                WHERE trade_date >= #{since}
                GROUP BY stage_name, consumer_type, trade_date
            ) t
            GROUP BY stage_name, consumer_type
            ORDER BY avgDailyIps DESC
            """)
    List<Map<String, Object>> avgDailyIps(@Param("since") LocalDate since);
}
