-- ============================================================================
-- Worker Service 统一调度 · 数据库变更 (openGauss 兼容版)
-- 1. crawl_task 增加 executor_type / job_type 字段
-- 2. worker_node 表（worker 注册与心跳）
-- 3. job_definition 表（job 定义/配置）
-- 4. job_execution 表（job 执行实例）
-- ============================================================================

-- ----------------------------------------------------------------------------
-- 1. crawl_task 新增字段 (openGauss 不支持 ADD COLUMN IF NOT EXISTS, 用 DO 块)
-- ----------------------------------------------------------------------------
DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM information_schema.columns WHERE table_name='crawl_task' AND column_name='executor_type') THEN
        ALTER TABLE crawl_task ADD COLUMN executor_type VARCHAR(16) NOT NULL DEFAULT 'JAVA';
    END IF;
    IF NOT EXISTS (SELECT 1 FROM information_schema.columns WHERE table_name='crawl_task' AND column_name='job_type') THEN
        ALTER TABLE crawl_task ADD COLUMN job_type VARCHAR(16) NOT NULL DEFAULT 'BATCH';
    END IF;
    IF NOT EXISTS (SELECT 1 FROM information_schema.columns WHERE table_name='crawl_task' AND column_name='worker_id') THEN
        ALTER TABLE crawl_task ADD COLUMN worker_id VARCHAR(64);
    END IF;
    IF NOT EXISTS (SELECT 1 FROM information_schema.columns WHERE table_name='crawl_task' AND column_name='progress_pct') THEN
        ALTER TABLE crawl_task ADD COLUMN progress_pct SMALLINT DEFAULT 0;
    END IF;
    IF NOT EXISTS (SELECT 1 FROM information_schema.columns WHERE table_name='crawl_task' AND column_name='pid') THEN
        ALTER TABLE crawl_task ADD COLUMN pid INTEGER;
    END IF;
END
$$;

COMMENT ON COLUMN crawl_task.executor_type IS '执行器类型: JAVA=Java Worker, PYTHON=Python Worker';
COMMENT ON COLUMN crawl_task.job_type IS '任务类型: BATCH=一次性, CONTINUOUS=持续运行';
COMMENT ON COLUMN crawl_task.worker_id IS '执行节点 ID';
COMMENT ON COLUMN crawl_task.progress_pct IS '进度百分比 (0-100, CONTINUOUS 用)';
COMMENT ON COLUMN crawl_task.pid IS '操作系统进程 PID';

-- 索引
CREATE INDEX IF NOT EXISTS idx_ct_executor_type ON crawl_task(executor_type);
CREATE INDEX IF NOT EXISTS idx_ct_job_type ON crawl_task(job_type);
CREATE INDEX IF NOT EXISTS idx_ct_worker_id ON crawl_task(worker_id);
CREATE INDEX IF NOT EXISTS idx_ct_created_at ON crawl_task(created_at);
CREATE INDEX IF NOT EXISTS idx_ct_date_status_executor ON crawl_task(CAST(created_at AS DATE), status, executor_type);

-- ----------------------------------------------------------------------------
-- 2. worker_node 表 (worker 注册、心跳、能力声明)
-- ----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS worker_node (
    worker_id       VARCHAR(64) PRIMARY KEY,
    executor_type   VARCHAR(16) NOT NULL DEFAULT 'JAVA',
    host_name       VARCHAR(128),
    ip_address      VARCHAR(64),
    pid             INTEGER,
    capabilities    TEXT,
    status          VARCHAR(16) NOT NULL DEFAULT 'OFFLINE',
    current_jobs    TEXT,
    last_heartbeat  TIMESTAMP,
    started_at      TIMESTAMP,
    created_at      TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at      TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);
COMMENT ON TABLE worker_node IS 'Worker 节点注册与心跳';
CREATE INDEX IF NOT EXISTS idx_wn_status ON worker_node(status);
CREATE INDEX IF NOT EXISTS idx_wn_executor_type ON worker_node(executor_type);
CREATE INDEX IF NOT EXISTS idx_wn_last_heartbeat ON worker_node(last_heartbeat);

-- ----------------------------------------------------------------------------
-- 3. job_definition 表 (job 定义、默认配置、调度规则)
-- ----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS job_definition (
    job_type        VARCHAR(64) PRIMARY KEY,
    display_name    VARCHAR(128) NOT NULL,
    executor_type   VARCHAR(16) NOT NULL DEFAULT 'PYTHON',
    job_category    VARCHAR(16) NOT NULL DEFAULT 'BATCH',
    description     TEXT,
    default_config  TEXT,
    schedule_cron   VARCHAR(64),
    market_dependent BOOLEAN DEFAULT false,
    auto_start      BOOLEAN DEFAULT false,
    enabled         BOOLEAN DEFAULT true,
    created_at      TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at      TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);
COMMENT ON TABLE job_definition IS 'Job 定义与默认配置';

-- 预置已知 job (openGauss 兼容: 用 WHERE NOT EXISTS 替代 ON CONFLICT)
INSERT INTO job_definition (job_type, display_name, executor_type, job_category, description, market_dependent, auto_start)
SELECT v.* FROM (VALUES
    ('L1_CAPTURE', 'L1 实时行情 (五档+逐笔)', 'PYTHON', 'CONTINUOUS', '通达信 L1 实时行情采集 → Redis → ClickHouse', true, true),
    ('REDIS_TO_CK', 'Redis→ClickHouse 消费', 'PYTHON', 'CONTINUOUS', 'Redis Stream 消费到 ClickHouse', true, true),
    ('L2_CAPTURE', 'L2 行情采集', 'PYTHON', 'CONTINUOUS', 'L2 行情数据采集', true, true),
    ('THS_ANOMALY', '同花顺异动', 'PYTHON', 'BATCH', '同花顺异动解读数据采集', false, false),
    ('BOARD_RELATION', '板块关联关系', 'PYTHON', 'BATCH', '股票与板块关联关系同步', false, false),
    ('DAILY_PIPELINE', '日批编排', 'JAVA', 'BATCH', '收盘后全阶段日批跑批', true, false),
    ('HISTORY_BACKFILL', '历史回填', 'JAVA', 'BATCH', '历史区间数据回填', false, false)
) AS v(job_type, display_name, executor_type, job_category, description, market_dependent, auto_start)
WHERE NOT EXISTS (
    SELECT 1 FROM job_definition jd WHERE jd.job_type = v.job_type
);

-- ----------------------------------------------------------------------------
-- 4. job_execution 表 (job 执行实例, 每天每 job 一条)
-- ----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS job_execution (
    execution_id    BIGSERIAL PRIMARY KEY,
    job_type        VARCHAR(64) NOT NULL REFERENCES job_definition(job_type),
    trade_date      DATE NOT NULL,
    worker_id       VARCHAR(64),
    status          VARCHAR(16) NOT NULL DEFAULT 'PENDING',
    started_at      TIMESTAMP,
    finished_at     TIMESTAMP,
    duration_ms     BIGINT,
    rows_affected   BIGINT DEFAULT 0,
    error_msg       TEXT,
    config_snapshot TEXT,
    created_at      TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at      TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (job_type, trade_date)
);
COMMENT ON TABLE job_execution IS 'Job 执行实例 (每天每 job 一条记录)';
CREATE INDEX IF NOT EXISTS idx_je_trade_date ON job_execution(trade_date);
CREATE INDEX IF NOT EXISTS idx_je_status ON job_execution(status);
CREATE INDEX IF NOT EXISTS idx_je_job_type ON job_execution(job_type);
CREATE INDEX IF NOT EXISTS idx_je_date_status ON job_execution(trade_date, status);
