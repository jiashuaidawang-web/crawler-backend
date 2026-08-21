-- schema-stock-anomaly.sql
-- 个股异动记录表（同花顺 ths_anomaly_data 产出）
-- 用途：存储每只股票的异动记录（大涨/大跌/涨停等），含原因、关键词
-- 唯一键：(ts_code, anomaly_id)，由 ReplacingMergeTree(data_source) 去重
-- anomaly_id 为同花顺全局唯一 ID，天然幂等

CREATE TABLE IF NOT EXISTS stock_anomaly ON CLUSTER clickhouse_cluster (
    ts_code         String NOT NULL COMMENT '个股代码(如 600000.SH)',
    anomaly_id      UInt64 NOT NULL COMMENT '同花顺异动唯一ID（去重键）',
    anomaly_date    Date COMMENT '异动日期',
    tag_code        String COMMENT '异动类型编码(如 SHARP_RISE/LIMIT_UP/SHARP_FALL)',
    tag_name        String COMMENT '异动类型中文(大涨/涨停/大跌)',
    reason          String COMMENT '异动原因（原文）',
    keywords        String COMMENT '关键词JSON数组(如 ["全球股市大跌"])',
    stock_name      String COMMENT '股票名称',
    feature         String COMMENT '原始JSON串（完整异动记录）',
    data_source     UInt8 DEFAULT 0 COMMENT '数据来源：0=同花顺',
    create_date     Date DEFAULT today() COMMENT '入库日期',
    update_date     DateTime DEFAULT now() COMMENT '更新时间'
) ENGINE = ReplacingMergeTree(data_source)
PARTITION BY toYYYYMM(anomaly_date)
ORDER BY (ts_code, anomaly_id)
SETTINGS index_granularity = 8192;

COMMENT ON TABLE stock_anomaly IS '个股异动记录（同花顺 ths_anomaly_data 产出）';
