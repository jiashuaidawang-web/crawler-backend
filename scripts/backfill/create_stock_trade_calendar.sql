-- A股交易日历表（ClickHouse）
-- 覆盖A股从1990-12-19开市至今的所有交易日
-- 引擎：ReplacingMergeTree(update_date) 保证幂等写入，同 trade_date 重复 seed 自动保留最新版
-- 独立于项目现有的 trade_calendar 表，互不干扰

CREATE TABLE IF NOT EXISTS stock_trade_calendar (
    trade_date   Date NOT NULL,
    is_trading   UInt8 NOT NULL DEFAULT 1,  -- 1=交易日 0=休市
    data_source  UInt8 NOT NULL DEFAULT 0,  -- 0=akshare 1=手动
    src_detail   Nullable(String),           -- 来源详情
    create_date  Nullable(Date),             -- 记录创建日期
    update_date  DateTime NOT NULL DEFAULT now()  -- 记录更新时间（ReplacingMergeTree 版本列，CK23.8要求非Nullable）
) ENGINE = ReplacingMergeTree(update_date)
ORDER BY trade_date
SETTINGS index_granularity = 8192;
