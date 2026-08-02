-- schema-stock-board-rel.sql
-- 板块-个股关系表（STOCK_BY_BOARD 任务的目标表）
-- 用途：记录每个板块包含哪些个股，用于板块分析、选股
-- 唯一键：(plate_code, ts_code, trade_date) 幂等

CREATE TABLE IF NOT EXISTS stock_board_rel (
    id              BIGSERIAL PRIMARY KEY,
    trade_date      DATE NOT NULL,
    plate_code      VARCHAR(20) NOT NULL COMMENT '板块代码(如 BK0450)',
    plate_name      VARCHAR(64) COMMENT '板块名称',
    plate_type      SMALLINT COMMENT '板块类型：1地域 2行业 3概念',
    ts_code         VARCHAR(12) NOT NULL COMMENT '股票代码(如 600000.SH)',
    stock_name      VARCHAR(64) COMMENT '股票名称',
    data_source     SMALLINT NOT NULL DEFAULT 1 COMMENT '数据来源：0东财 1同花顺',
    create_date     DATE DEFAULT CURRENT_DATE,
    update_date     TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (plate_code, ts_code, trade_date)
);

COMMENT ON TABLE stock_board_rel IS '板块-个股关系（STOCK_BY_BOARD 任务产出）';
COMMENT ON COLUMN stock_board_rel.plate_type IS '1地域 2行业 3概念';
