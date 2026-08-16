package com.dunwugudao.crawler.persistence.mapper;

import com.dunwugudao.crawler.persistence.entity.CrawlTask;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;
import java.util.Map;

/**
 * crawl_task Mapper。核心认领用 {@link #claim} 的原生 SQL。
 */
@Mapper
public interface CrawlTaskMapper {

    /** 按主键查询（替代已移除的 BaseMapper.selectById）。 */
    @Select("SELECT * FROM crawl_task WHERE task_id = #{taskId}")
    CrawlTask selectById(@Param("taskId") Long taskId);

    /**
     * 分布式认领：在 PENDING/RETRY 且未到 next_retry_at 的任务中按优先级/创建时间取前 batch 条，
     * 加 {@code FOR UPDATE SKIP LOCKED} 避免多节点重复认领，置 CLAIMED 并返回被认领的行。
     * <p>openGauss 3.x 支持 {@code FOR UPDATE SKIP LOCKED} 与 {@code RETURNING *}。</p>
     */
    @Select("""
            UPDATE crawl_task SET status = 'CLAIMED', last_node = #{nodeId}, started_at = now()
             WHERE task_id IN (
               SELECT task_id FROM crawl_task
               WHERE status IN ('PENDING', 'RETRY')
                 AND (next_retry_at IS NULL OR next_retry_at <= now())
               ORDER BY priority DESC, created_at ASC
               LIMIT #{batch}
               FOR UPDATE SKIP LOCKED
             ) RETURNING *
            """)
    List<CrawlTask> claim(@Param("batch") int batch, @Param("nodeId") String nodeId);

    @Select("SELECT status, count(*) AS cnt FROM crawl_task GROUP BY status ORDER BY status")
    List<Map<String, Object>> countByStatus();

    /** 按日期统计各状态任务数(日期编码在 unique_key 末尾,格式 taskType|source|date[*pn])。 */
    @Select("SELECT status, count(*) AS cnt FROM crawl_task " +
            "WHERE unique_key LIKE ('%' || #{date}) GROUP BY status ORDER BY status")
    List<Map<String, Object>> countByStatusDate(@Param("date") String date);

    @Select("SELECT source, count(*) AS cnt FROM crawl_task GROUP BY source ORDER BY source")
    List<Map<String, Object>> countBySource();

    @Select("SELECT COALESCE(last_node, 'unknown') AS node, count(*) AS cnt " +
            "FROM crawl_task GROUP BY last_node ORDER BY cnt DESC")
    List<Map<String, Object>> countByNode();

    /** 按 unique_key 前缀 + 未终态(PENDING/CLAIMED)统计,用于编排阶段完成探测。 */
    @Select("SELECT status, count(*) AS cnt FROM crawl_task " +
            "WHERE unique_key LIKE #{like} AND status IN ('PENDING','RETRY') " +
            "GROUP BY status")
    List<Map<String, Object>> countByStatusLike(@Param("like") String like);

    /** 按 unique_key 前缀统计全部状态分布。 */
    @Select("SELECT status, count(*) AS cnt FROM crawl_task WHERE unique_key LIKE #{like} GROUP BY status")
    List<Map<String, Object>> countAllLike(@Param("like") String like);

    // ---------------------- M3：种子生成（幂等） ----------------------

    /**
     * 幂等单行插入：unique_key 冲突则忽略，返回受影响行数（冲突=0）。
     * 使用 WHERE NOT EXISTS 兼容 openGauss（不支持 ON CONFLICT DO NOTHING）。
     */
    @Insert("""
            INSERT INTO crawl_task (task_type, source, url, params_json, status, priority, retry_count, max_retry, unique_key, expected_count, created_at, updated_at)
            SELECT #{t.taskType}, #{t.source.code}, #{t.url}, #{t.paramsJson}, 'PENDING', #{t.priority}, 0, #{t.maxRetry}, #{t.uniqueKey}, #{t.expectedCount}, now(), now()
            WHERE NOT EXISTS (SELECT 1 FROM crawl_task WHERE unique_key = #{t.uniqueKey})
            """)
    int insertIfAbsent(@Param("t") CrawlTask t);

    /**
     * 幂等批量插入：unique_key 冲突则忽略该批冲突行，返回受影响行数。
     */
    @Insert("""
            <script>
            INSERT INTO crawl_task (task_type, source, url, params_json, status, priority, retry_count, max_retry, unique_key, expected_count, created_at, updated_at)
            <foreach collection="list" item="t" separator="UNION ALL">
            SELECT #{t.taskType}, #{t.source.code}, #{t.url}, #{t.paramsJson}, 'PENDING', #{t.priority}, 0, #{t.maxRetry}, #{t.uniqueKey}, #{t.expectedCount}, now(), now()
            WHERE NOT EXISTS (SELECT 1 FROM crawl_task WHERE unique_key = #{t.uniqueKey})
            </foreach>
            </script>
            """)
    int batchInsertIfAbsent(@Param("list") java.util.List<CrawlTask> list);

    // ---------------------- M3：重试扫描（僵尸回收） ----------------------

    /**
     * 僵尸回收：把 status='CLAIMED' 且 started_at 早于 now()-timeoutMin 的任务重置回 PENDING、清空 last_node、
     * error_msg 前缀加 'reclaimed by retryScan'。补齐「节点崩溃导致任务卡在 CLAIMED」的缺口。
     */
    @Update("""
            UPDATE crawl_task SET status='PENDING', last_node=NULL,
              error_msg = CASE WHEN error_msg IS NULL THEN 'reclaimed by retryScan' ELSE 'reclaimed by retryScan; ' || error_msg END,
              updated_at=now()
            WHERE status='CLAIMED' AND started_at < now() - make_interval(mins => #{timeoutMin})
            """)
    int reclaimZombies(@Param("timeoutMin") int timeoutMin);

    /**
     * 兜底：把 status='RETRY' 且 retry_count >= max_retry 的任务置 DEAD，避免死循环重排。
     */
    @Update("""
            UPDATE crawl_task SET status='DEAD', finished_at=now(),
              error_msg = CASE WHEN error_msg IS NULL THEN 'max retry exceeded' ELSE 'max retry exceeded; ' || error_msg END,
              updated_at=now()
            WHERE status='RETRY' AND retry_count >= max_retry
            """)
    int promoteExhausted();

    // ---------------------- 编排器 force 重跑 ----------------------
    /**
     * 强制重跑:把某日期下 DEAD/FAILED 的任务重置为 PENDING,让 worker 重新认领。
     * <p>seed 幂等会跳过已存在的任务,但这些任务状态已是终态无法被认领,需先重置。</p>
     *
     * @return 重置的任务数
     */
    @Update("""
            UPDATE crawl_task SET status='PENDING', retry_count=0, next_retry_at=NULL,
              last_node=NULL, started_at=NULL, finished_at=NULL, actual_count=NULL,
              error_msg='force reset by pipeline re-run', updated_at=now()
            WHERE unique_key LIKE #{like} AND status IN ('DEAD','FAILED')
            """)
    int forceResetDeadTasks(@Param("like") String like);

    /**
     * 重置某日期前缀下指定状态的任务为 PENDING(用于手动重试失败阶段)。
     *
     * @param like      unique_key LIKE 前缀(如 "LIMIT_UP|1|2026-08-14%")
     * @param statuses  要重置的状态列表
     * @return 重置的任务数
     */
    @Update("""
            <script>
            UPDATE crawl_task SET status='PENDING', retry_count=0, next_retry_at=NULL,
              last_node=NULL, started_at=NULL, finished_at=NULL, actual_count=NULL,
              error_msg='manual retry by user', updated_at=now()
            WHERE unique_key LIKE #{like}
              AND status IN
              <foreach collection='statuses' item='s' open='(' separator=',' close=')'>
                #{s}
              </foreach>
            </script>
            """)
    int resetTasksByStatus(@Param("like") String like, @Param("statuses") List<String> statuses);

    /** 统计某日期前缀下各状态任务数(用于展示失败任务分布) */
    @Select("""
            SELECT status, count(*) AS cnt FROM crawl_task
            WHERE unique_key LIKE #{like}
            GROUP BY status
            """)
    List<java.util.Map<String, Object>> countByStatusForLike(@Param("like") String like);
}
