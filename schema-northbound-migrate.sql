-- ============================================================
-- 迁移：扩展 northbound_flow 表（添加 direction + 完整字段）
-- ============================================================

-- 1. 删除旧表（数据可重新抓取）
DROP TABLE IF EXISTS northbound_flow;

-- 2. 创建新表
CREATE TABLE northbound_flow (
    trade_date            Date NOT NULL,
    data_source           UInt8 NOT NULL DEFAULT 1,
    direction             String NOT NULL,           -- 's2n'（沪深→港）或 'n2s'（港→沪深）
    time_point            String NOT NULL,           -- 时间点（如 9:30）
    net_inflow            Nullable(Decimal64(2)),    -- 净流入（万元）
    buy_amount            Nullable(Decimal64(2)),    -- 买入额（万元）
    sell_amount           Nullable(Decimal64(2)),    -- 卖出额（万元）
    cumulative_net_inflow Nullable(Decimal64(2)),    -- 累计净流入（万元）
    status_flag           Nullable(Decimal64(2)),    -- 状态标记
    src_detail            Nullable(String),
    create_date           Nullable(Date),
    update_date           DateTime NOT NULL DEFAULT toDateTime(0)
) ENGINE = ReplacingMergeTree(update_date)
PARTITION BY toYYYYMM(trade_date)
ORDER BY (trade_date, data_source, direction, time_point)
SETTINGS index_granularity = 8192;

CREATE INDEX IF NOT EXISTS idx_nbf_date ON northbound_flow(trade_date, data_source);
