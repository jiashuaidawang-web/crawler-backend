-- stock_daily 新增 9 列（对接 push2 clist f 码完整投影，2026-08-02）
-- 源：push2.eastmoney.com/api/qt/clist/get 全市场快照接口（f1-f173）

ALTER TABLE stock_daily ADD COLUMN velocity        NUMERIC(10,4);   -- f11  涨速%
ALTER TABLE stock_daily ADD COLUMN is_new_high     SMALLINT;        -- f22  是否新高 1/0
ALTER TABLE stock_daily ADD COLUMN chg_60d         NUMERIC(10,4);   -- f23  60日涨跌幅%
ALTER TABLE stock_daily ADD COLUMN seal_fund       NUMERIC(24,2);   -- f62  封单资金(元)
ALTER TABLE stock_daily ADD COLUMN board_days      SMALLINT;        -- f115 连板天数
ALTER TABLE stock_daily ADD COLUMN board_stat      VARCHAR(16);     -- f128 涨停统计("3/2")
ALTER TABLE stock_daily ADD COLUMN first_seal_time VARCHAR(8);      -- f140 首次封板 HH:mm:ss
ALTER TABLE stock_daily ADD COLUMN last_seal_time  VARCHAR(8);      -- f141 最后封板 HH:mm:ss
ALTER TABLE stock_daily ADD COLUMN limit_type      SMALLINT;        -- f152 涨停类型

-- 修正注释（之前被错误复用的列）
COMMENT ON COLUMN stock_daily.reserved_f24 IS 'f24 年初至今涨跌幅%';
COMMENT ON COLUMN stock_daily.reserved_f25 IS 'f25 涨停价(分→元)';
COMMENT ON COLUMN stock_daily.reserved_f136 IS 'f136 炸板次数';
COMMENT ON COLUMN stock_daily.reserved_f173 IS 'f173 涨速%';
COMMENT ON COLUMN stock_daily.reserved_f107 IS '预留 f107';

COMMENT ON COLUMN stock_daily.velocity        IS 'f11 涨速%';
COMMENT ON COLUMN stock_daily.is_new_high     IS 'f22 是否新高 1/0';
COMMENT ON COLUMN stock_daily.chg_60d         IS 'f23 60日涨跌幅%';
COMMENT ON COLUMN stock_daily.seal_fund       IS 'f62 封单资金(元)';
COMMENT ON COLUMN stock_daily.board_days      IS 'f115 连板天数';
COMMENT ON COLUMN stock_daily.board_stat      IS 'f128 涨停统计("3/2"表示3天2板)';
COMMENT ON COLUMN stock_daily.first_seal_time IS 'f140 首次封板时间 HH:mm:ss';
COMMENT ON COLUMN stock_daily.last_seal_time  IS 'f141 最后封板时间 HH:mm:ss';
COMMENT ON COLUMN stock_daily.limit_type      IS 'f152 涨停类型';
