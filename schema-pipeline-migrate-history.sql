-- ============================================================
-- 迁移:支持同日多次跑批(历史 run 列表)
-- 去掉 pipeline_run.run_date 的 UNIQUE 约束,改以 run_id 区分每次执行。
-- ============================================================

-- 1. 去掉 UNIQUE 约束(保留 run_id 主键)
ALTER TABLE pipeline_run DROP CONSTRAINT IF EXISTS uq_pipeline_run_date;

-- 2. 加索引(按日期倒序列出历史 run)
CREATE INDEX IF NOT EXISTS idx_pipeline_run_date ON pipeline_run(run_date, run_id DESC);
