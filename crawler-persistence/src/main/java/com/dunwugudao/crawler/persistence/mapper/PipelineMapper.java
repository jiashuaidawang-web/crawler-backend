package com.dunwugudao.crawler.persistence.mapper;

import com.dunwugudao.crawler.persistence.entity.PipelineRun;
import com.dunwugudao.crawler.persistence.entity.PipelineStageRecord;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.SelectKey;
import org.apache.ibatis.annotations.Update;
import java.time.LocalDate;
import java.util.List;

/** pipeline_run / pipeline_stage Mapper。编排状态落库。 */
public interface PipelineMapper {

    // ---------------- pipeline_run ----------------

    @Insert("INSERT INTO pipeline_run (run_date, status, started_at) " +
            "SELECT #{r.runDate}, 'RUNNING', now() " +
            "WHERE NOT EXISTS (SELECT 1 FROM pipeline_run WHERE run_date = #{r.runDate})")
    int insertRunIgnoreConflict(@Param("r") PipelineRun r);

    /** 始终新建一条 run 并返回 runId(支持同日多次跑批)。 */
    @Insert("INSERT INTO pipeline_run (run_date, status, started_at) VALUES (#{r.runDate}, 'RUNNING', now())")
    @SelectKey(statement = "SELECT currval('pipeline_run_run_id_seq')",
               keyProperty = "runId", before = false, resultType = Long.class)
    int insertRun(@Param("r") PipelineRun r);

    @Select("SELECT run_id FROM pipeline_run WHERE run_date = #{date} ORDER BY run_id DESC LIMIT 1")
    Long selectRunIdByDate(@Param("date") LocalDate date);

    /** 列出某日所有 run(倒序:最新在前)。用于历史跑批列表。 */
    @Select("SELECT * FROM pipeline_run WHERE run_date = #{date} ORDER BY run_id DESC")
    List<PipelineRun> selectRunsByDate(@Param("date") LocalDate date);

    /** 查询某日最新一条 run。 */
    @Select("SELECT * FROM pipeline_run WHERE run_date = #{date} ORDER BY run_id DESC LIMIT 1")
    PipelineRun selectRunByDate(@Param("date") LocalDate date);

    /** 查询单 run(含 stages)。 */
    @Select("SELECT * FROM pipeline_run WHERE run_id = #{runId}")
    PipelineRun selectRunById(@Param("runId") Long runId);

    @Update("UPDATE pipeline_run SET status=#{status}, finished_at=now(), summary=#{summary} WHERE run_id=#{runId}")
    int finishRun(@Param("runId") Long runId, @Param("status") String status, @Param("summary") String summary);

    @Update("UPDATE pipeline_run SET status='RUNNING', summary=NULL, finished_at=NULL WHERE run_id=#{runId}")
    int resetRunToRunning(@Param("runId") Long runId);

    @Update("UPDATE pipeline_run SET status='ABORTED', finished_at=now() WHERE run_date=#{date} AND status='RUNNING'")
    int abortStaleRuns(@Param("date") LocalDate date);

    /** 查某阶段历史平均 seeded_count(近 N 天,用于判断今天是否下发完整)。 */
    @Select("""
            SELECT CAST(AVG(seeded_count) AS INT) AS avg_seeded, COUNT(*) AS sample_count
            FROM pipeline_stage ps JOIN pipeline_run pr ON ps.run_id = pr.run_id
            WHERE ps.stage_name = #{stageName}
              AND ps.status IN ('DONE','FAILED')
              AND ps.seeded_count > 0
              AND pr.run_date >= #{since}
            """)
    java.util.Map<String, Object> selectAvgSeeded(@Param("stageName") String stageName, @Param("since") LocalDate since);

    // ---------------- pipeline_stage ----------------

    /** 预建单阶段(PENDING),幂等(已存在则不重复插)。用于 run 初始化。 */
    @Insert("INSERT INTO pipeline_stage (run_id, stage_name, seq, status) " +
            "SELECT #{runId}, #{name}, #{seq}, 'PENDING' " +
            "WHERE NOT EXISTS (SELECT 1 FROM pipeline_stage WHERE run_id=#{runId} AND stage_name=#{name})")
    int insertStageIfNotExists(@Param("runId") Long runId, @Param("name") String name, @Param("seq") int seq);

    /** 重置某 run 下所有阶段为 PENDING(重跑前)。 */
    @Update("UPDATE pipeline_stage SET status='PENDING', seeded_count=NULL, expected_total=NULL, " +
            "actual_total=NULL, dup_rows=NULL, lost_rows=NULL, duration_ms=NULL, " +
            "check_result=NULL, error_msg=NULL, started_at=NULL, finished_at=NULL " +
            "WHERE run_id=#{runId}")
    int resetStagesToPending(@Param("runId") Long runId);

    /** 按 (run_id,stage_name) 更新阶段(执行时写结果,不新建行)。 */
    @Update("UPDATE pipeline_stage " +
            "SET status=#{s.status}, seeded_count=#{s.seededCount}, expected_total=#{s.expectedTotal}, " +
            "actual_total=#{s.actualTotal}, dup_rows=#{s.dupRows}, lost_rows=#{s.lostRows}, " +
            "duration_ms=#{s.durationMs}, check_result=#{s.checkResult}, error_msg=#{s.errorMsg}, " +
            "started_at=COALESCE(started_at, now()), finished_at=now() " +
            "WHERE run_id=#{s.runId} AND stage_name=#{s.stageName}")
    int updateStageByName(@Param("s") PipelineStageRecord s);

    @Select("SELECT * FROM pipeline_stage WHERE run_id=#{runId} AND stage_name=#{name}")
    PipelineStageRecord selectStage(@Param("runId") Long runId, @Param("name") String name);

    @Select("SELECT * FROM pipeline_stage WHERE run_id=#{runId} ORDER BY seq")
    List<PipelineStageRecord> selectStages(@Param("runId") Long runId);
}
