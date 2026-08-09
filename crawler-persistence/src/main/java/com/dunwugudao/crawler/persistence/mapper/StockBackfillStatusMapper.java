package com.dunwugudao.crawler.persistence.mapper;

import com.dunwugudao.crawler.persistence.entity.StockBackfillStatus;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDate;
import java.util.List;

/**
 * crawl_stock_backfill_status Mapper（openGauss，注解风格）。
 * <p>股票日K历史回填进度：每只股票一行，支持断点续传。</p>
 */
@Mapper
public interface StockBackfillStatusMapper {

    /** 单行进度（按 ts_code 查）。 */
    @Select("SELECT ts_code, status, earliest_date, latest_date, row_count, error_msg, updated_at " +
            "FROM crawl_stock_backfill_status WHERE ts_code = #{tsCode}")
    StockBackfillStatus selectByTsCode(@Param("tsCode") String tsCode);

    /** 全部进度（按状态过滤，null 表示全部）。 */
    @Select("<script>" +
            "SELECT ts_code, status, earliest_date, latest_date, row_count, error_msg, updated_at " +
            "FROM crawl_stock_backfill_status" +
            "<where>" +
            "<if test='status != null'> AND status = #{status}</if>" +
            "</where> ORDER BY ts_code" +
            "</script>")
    List<StockBackfillStatus> selectAll(@Param("status") String status);

    /** 全部已是 SUCCESS 的 ts_code（用于批量过滤）。 */
    @Select("SELECT ts_code FROM crawl_stock_backfill_status WHERE status = 'SUCCESS'")
    List<String> selectSuccessCodes();

    /** 进度汇总：各状态股票数。 */
    @Select("SELECT status, count(*) AS cnt FROM crawl_stock_backfill_status GROUP BY status ORDER BY status")
    List<java.util.Map<String, Object>> countByStatus();

    /**
     * 幂等 upsert：ts_code 不存在则插入 PENDING，存在则忽略。
     * 返回受影响行数（插入=1，已存在=0）。
     */
    @Insert("""
            INSERT INTO crawl_stock_backfill_status (ts_code, status, updated_at)
            SELECT #{tsCode}, 'PENDING', now()
            WHERE NOT EXISTS (SELECT 1 FROM crawl_stock_backfill_status WHERE ts_code = #{tsCode})
            """)
    int insertIfAbsent(@Param("tsCode") String tsCode);

    /** 批量幂等 upsert（从股票列表初始化进度表）。 */
    @Insert("""
            <script>
            INSERT INTO crawl_stock_backfill_status (ts_code, status, updated_at)
            <foreach collection="list" item="tsCode" separator="UNION ALL">
            SELECT #{tsCode}, 'PENDING', now()
            WHERE NOT EXISTS (SELECT 1 FROM crawl_stock_backfill_status WHERE ts_code = #{tsCode})
            </foreach>
            </script>
            """)
    int batchInsertIfAbsent(@Param("list") List<String> tsCodes);

    /** 标记 RUNNING（认领执行时）。 */
    @Update("UPDATE crawl_stock_backfill_status SET status = 'RUNNING', updated_at = now() WHERE ts_code = #{tsCode}")
    int markRunning(@Param("tsCode") String tsCode);

    /** 标记 SUCCESS（回填完成）：回填行数 + 最早/最晚日期。 */
    @Update("""
            UPDATE crawl_stock_backfill_status
            SET status = 'SUCCESS', row_count = #{rowCount},
                earliest_date = #{earliestDate}, latest_date = #{latestDate},
                error_msg = NULL, updated_at = now()
            WHERE ts_code = #{tsCode}
            """)
    int markSuccess(@Param("tsCode") String tsCode,
                    @Param("rowCount") int rowCount,
                    @Param("earliestDate") LocalDate earliestDate,
                    @Param("latestDate") LocalDate latestDate);

    /** 标记 FAILED（回填失败）：记录原因。 */
    @Update("""
            UPDATE crawl_stock_backfill_status
            SET status = 'FAILED', error_msg = #{errorMsg}, updated_at = now()
            WHERE ts_code = #{tsCode}
            """)
    int markFailed(@Param("tsCode") String tsCode, @Param("errorMsg") String errorMsg);

    /** 重置单只股票为 PENDING（失败重跑用）。 */
    @Update("UPDATE crawl_stock_backfill_status SET status = 'PENDING', error_msg = NULL, updated_at = now() WHERE ts_code = #{tsCode}")
    int resetToPending(@Param("tsCode") String tsCode);

    /** 全部重置为 PENDING（全量重跑用）。 */
    @Update("UPDATE crawl_stock_backfill_status SET status = 'PENDING', error_msg = NULL, updated_at = now()")
    int resetAllToPending();
}
