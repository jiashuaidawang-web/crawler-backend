-- V2 P0-1: 同花顺板块基础维表（THS_PLATE 任务产出）
-- 与 board_basic（东财, board_type=1/2/3）并列，plate_type 用 4/5/6 区分来源
-- 主键 (plate_type, plate_code, trade_date)，由 MergeTree ORDER BY 承接去重

CREATE TABLE IF NOT EXISTS ths_plate ON CLUSTER clickhouse_cluster (
    id              UInt64,
    plate_type      UInt8 NOT NULL COMMENT '板块类型：4地域 5行业 6概念（与东财 1/2/3 区分）',
    plate_code      String NOT NULL COMMENT '同花顺板块代码（如 307408）',
    plate_name      String NOT NULL COMMENT '板块名称',
    plate_index     String COMMENT '板块指数代码',
    lead_stock_code String COMMENT '领涨股代码',
    lead_stock_name String COMMENT '领涨股名称',
    cur_price       Decimal64(4) COMMENT '当前价格',
    increase        Decimal64(4) COMMENT '涨跌幅%',
    turnover        Decimal64(2) COMMENT '成交额(元)',
    volume          UInt64 COMMENT '成交量(手)',
    up_count        UInt16 COMMENT '上涨家数',
    down_count      UInt16 COMMENT '下跌家数',
    trade_date      Date NOT NULL COMMENT '数据日期',
    data_source     UInt8 DEFAULT 0 COMMENT '0=同花顺',
    src_detail      String DEFAULT '' COMMENT '溯源 URL',
    create_date     DateTime DEFAULT now(),
    update_date     DateTime DEFAULT now()
) ENGINE = MergeTree()
PARTITION BY toYYYYMM(trade_date)
ORDER BY (plate_type, plate_code, trade_date)
SETTINGS index_granularity = 8192;

COMMENT ON TABLE ths_plate IS '同花顺板块基础维表（V2 P0-1 THS_PLATE 产出）';
