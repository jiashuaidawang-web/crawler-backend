-- ============================================================
-- 端到端日批编排 + 自动化校验:编排状态表(openGauss)
-- ============================================================
-- 跑批 pipeline 的一次执行 = pipeline_run;每个阶段 = pipeline_stage。
-- 幂等关键:pipeline_run.UNIQUE(run_date) —— 同日只一条,重跑走 resume。

CREATE TABLE pipeline_run (
    run_id      BIGSERIAL PRIMARY KEY,
    run_date    DATE NOT NULL,
    status      VARCHAR(16) NOT NULL,       -- RUNNING / SUCCESS / FAILED / ABORTED
    started_at  TIMESTAMP NOT NULL,
    finished_at TIMESTAMP,
    summary     TEXT,                       -- 跑批摘要 JSON(各阶段耗时/量/告警数)
    CONSTRAINT uq_pipeline_run_date UNIQUE (run_date)
);

CREATE INDEX idx_pipeline_run_status ON pipeline_run(status);

CREATE TABLE pipeline_stage (
    stage_id      BIGSERIAL PRIMARY KEY,
    run_id        BIGINT NOT NULL REFERENCES pipeline_run(run_id),
    stage_name    VARCHAR(32) NOT NULL,     -- 对应 PipelineStage 枚举名
    seq           SMALLINT NOT NULL,        -- 执行顺序
    status        VARCHAR(16) NOT NULL,     -- PENDING / RUNNING / SKIP / FAILED / DONE / IGNORED
    seeded_count  INT,                      -- 本次下发任务数
    expected_total INT,                     -- 上游总数(校验真值)
    actual_total  INT,                      -- CK 实际行数(去重后)
    dup_rows      INT,                      -- 重复行数(去重解释)
    lost_rows     INT,                      -- 真正丢失行数
    duration_ms   BIGINT,
    check_result  TEXT,                     -- 校验明细 JSON(逐任务)
    error_msg     TEXT,
    started_at    TIMESTAMP,
    finished_at   TIMESTAMP
);

CREATE INDEX idx_pipeline_stage_run ON pipeline_stage(run_id);
