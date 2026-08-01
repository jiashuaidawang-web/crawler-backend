package com.dunwugudao.crawler.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
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
public interface CrawlTaskMapper extends BaseMapper<CrawlTask> {

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

    @Select("SELECT source, count(*) AS cnt FROM crawl_task GROUP BY source ORDER BY source")
    List<Map<String, Object>> countBySource();

    @Select("SELECT COALESCE(last_node, 'unknown') AS node, count(*) AS cnt " +
            "FROM crawl_task GROUP BY last_node ORDER BY cnt DESC")
    List<Map<String, Object>> countByNode();

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
}
