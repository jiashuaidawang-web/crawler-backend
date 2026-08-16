-- ============================================================
-- IP 消耗统计表（admin 探测 + worker 爬取）
-- ============================================================

CREATE TABLE IF NOT EXISTS crawl_ip_consumption (
    id              BIGSERIAL PRIMARY KEY,
    task_id         BIGINT,             -- worker 端关联 crawl_task（admin 端为 NULL）
    consumer_type   VARCHAR(16) NOT NULL, -- ADMIN（探测）/ WORKER（爬取）
    stage_name      VARCHAR(32),        -- 业务阶段（股票日线/涨停池...）
    task_type       VARCHAR(32),        -- 具体任务类型（LIMIT_UP/STOCK_DAILY...）
    proxy_ip        VARCHAR(64),        -- 使用的代理 IP
    agent_type      VARCHAR(32),        -- 代理商类型（青果/其他）
    request_time    TIMESTAMP NOT NULL DEFAULT now(),
    response_status VARCHAR(16),        -- SUCCESS/FAILED/EMPTY/TIMEOUT
    response_bytes  INT,                -- 响应字节数
    duration_ms     BIGINT,             -- 耗时 ms
    error_msg       TEXT,               -- 错误信息
    trade_date      DATE                -- 交易日期（冗余，方便查询）
);

CREATE INDEX IF NOT EXISTS idx_ip_cons_stage ON crawl_ip_consumption(stage_name, trade_date);
CREATE INDEX IF NOT EXISTS idx_ip_cons_agent ON crawl_ip_consumption(agent_type, trade_date);
CREATE INDEX IF NOT EXISTS idx_ip_cons_consumer ON crawl_ip_consumption(consumer_type, trade_date);
CREATE INDEX IF NOT EXISTS idx_ip_cons_time ON crawl_ip_consumption(request_time);
