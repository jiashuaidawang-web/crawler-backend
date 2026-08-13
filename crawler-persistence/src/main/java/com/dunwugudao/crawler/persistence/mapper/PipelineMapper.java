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

    @Select("SELECT run_id FROM pipeline_run WHERE run_date = #{date} ORDER BY run_id DESC LIMIT 1")
    Long selectRunIdByDate(@Param("date") LocalDate date);

    @Select("SELECT * FROM pipeline_run WHERE run_date = #{date}")
    PipelineRun selectRunByDate(@Param("date") LocalDate date);

    @Update("UPDATE pipeline_run SET status=#{status}, finished_at=now(), summary=#{summary} WHERE run_id=#{runId}")
    int finishRun(@Param("runId") Long runId, @Param("status") String status, @Param("summary") String summary);

    @Update("UPDATE pipeline_run SET status='ABORTED', finished_at=now() WHERE run_date=#{date} AND status='RUNNING'")
    int abortStaleRuns(@Param("date") LocalDate date);

    // ---------------- pipeline_stage ----------------

    @Insert("""
            INSERT INTO pipeline_stage (run_id, stage_name, seq, status, started_at)
            VALUES (#{s.runId}, #{s.stageName}, #{s.seq}, 'RUNNING', now())
            """)
    @SelectKey(statement = "SELECT currval('pipeline_stage_stage_id_seq')",
            keyProperty = "s.stageId", before = false, resultType = Long.class)
    int insertStage(@Param("s") PipelineStageRecord s);

    @Update("""
            UPDATE pipeline_stage
            SET status=#{s.status}, seeded_count=#{s.seededCount}, expected_total=#{s.expectedTotal},
                actual_total=#{s.actualTotal}, dup_rows=#{s.dupRows}, lost_rows=#{s.lostRows},
                duration_ms=#{s.durationMs}, check_result=#{s.checkResult}, error_msg=#{s.errorMsg},
                finished_at=now()
            WHERE stage_id=#{s.stageId}
            """)
    int updateStage(@Param("s") PipelineStageRecord s);

    @Select("SELECT * FROM pipeline_stage WHERE run_id=#{runId} ORDER BY seq")
    List<PipelineStageRecord> selectStages(@Param("runId") Long runId);
}
