-- ============================================================================
-- 股票复盘系统 · 数据模型（更新版 2026-08-01）
-- 来源方法论：《顿悟股道》无门问禅（8 个 skill）
-- 约定：金额单位=元；幅度/涨跌=百分比数值；成交量=手
-- 基础字段：data_source(来源), src_detail(溯源), create_date(创建日期), update_date(修改日期)
-- 运行：mysql -u stock -p stock < schema-update-fields.sql
-- ============================================================================

-- ============================================================================
-- 一、新建表（board_basic 不存在，直接创建）
-- ============================================================================

CREATE TABLE board_basic (
  id           BIGSERIAL PRIMARY KEY,
  board_type   SMALLINT NOT NULL,             -- 1：地域 2：行业 3：概念
  code         VARCHAR(16),                   -- 同花顺板块指数代码
  board_code   VARCHAR(16) NOT NULL,          -- 板块代号(如 BK0450)
  board_name   VARCHAR(64) NOT NULL,          -- 板块名称
  features     VARCHAR(340),                  -- 备用字段
  status       SMALLINT DEFAULT 1,             -- 1=正常 0=删除
  data_source  SMALLINT NOT NULL,             -- 0=东财 1=同花顺
  create_date  DATE NOT NULL,                 -- 创建日期
  update_date  DATETIME                       -- 修改日期(初始NULL)
);
COMMENT ON TABLE board_basic IS '板块基础数据表；存储板块基本信息，用于增量分析和概念股票归类';
CREATE INDEX idx_bb_board_code ON board_basic(board_code);
CREATE INDEX idx_bb_board_type ON board_basic(board_type);

-- ============================================================================
-- 二、已存在表加基础字段（data_source, src_detail, create_date, update_date）
-- ============================================================================

-- A1 指数日线
ALTER TABLE index_daily ADD COLUMN data_source SMALLINT NOT NULL DEFAULT 1;
ALTER TABLE index_daily ADD COLUMN src_detail VARCHAR(256);
ALTER TABLE index_daily ADD COLUMN create_date DATE;
ALTER TABLE index_daily ADD COLUMN update_date DATETIME;

-- A2 个股日线
ALTER TABLE stock_daily ADD COLUMN data_source SMALLINT NOT NULL DEFAULT 1;
ALTER TABLE stock_daily ADD COLUMN src_detail VARCHAR(256);
ALTER TABLE stock_daily ADD COLUMN create_date DATE;
ALTER TABLE stock_daily ADD COLUMN update_date DATETIME;

-- A3 个股周线
ALTER TABLE stock_weekly ADD COLUMN data_source SMALLINT NOT NULL DEFAULT 1;
ALTER TABLE stock_weekly ADD COLUMN src_detail VARCHAR(256);
ALTER TABLE stock_weekly ADD COLUMN create_date DATE;
ALTER TABLE stock_weekly ADD COLUMN update_date DATETIME;

-- A4 涨跌停/炸板池
ALTER TABLE limit_pool ADD COLUMN data_source SMALLINT NOT NULL DEFAULT 1;
ALTER TABLE limit_pool ADD COLUMN src_detail VARCHAR(256);
ALTER TABLE limit_pool ADD COLUMN create_date DATE;
ALTER TABLE limit_pool ADD COLUMN update_date DATETIME;

-- A5 强势股池
ALTER TABLE strong_pool ADD COLUMN data_source SMALLINT NOT NULL DEFAULT 1;
ALTER TABLE strong_pool ADD COLUMN src_detail VARCHAR(256);
ALTER TABLE strong_pool ADD COLUMN create_date DATE;
ALTER TABLE strong_pool ADD COLUMN update_date DATETIME;

-- A6 板块日线
ALTER TABLE board_daily ADD COLUMN data_source SMALLINT NOT NULL DEFAULT 1;
ALTER TABLE board_daily ADD COLUMN src_detail VARCHAR(256);
ALTER TABLE board_daily ADD COLUMN create_date DATE;
ALTER TABLE board_daily ADD COLUMN update_date DATETIME;

-- A7 股票-板块关联
ALTER TABLE stock_board_rel ADD COLUMN data_source SMALLINT NOT NULL DEFAULT 1;
ALTER TABLE stock_board_rel ADD COLUMN src_detail VARCHAR(256);
ALTER TABLE stock_board_rel ADD COLUMN create_date DATE;
ALTER TABLE stock_board_rel ADD COLUMN update_date DATETIME;

-- A8 龙虎榜
ALTER TABLE dragon_tiger ADD COLUMN data_source SMALLINT NOT NULL DEFAULT 1;
ALTER TABLE dragon_tiger ADD COLUMN src_detail VARCHAR(256);
ALTER TABLE dragon_tiger ADD COLUMN create_date DATE;
ALTER TABLE dragon_tiger ADD COLUMN update_date DATETIME;

-- A9 龙虎榜席位明细
ALTER TABLE dt_detail ADD COLUMN data_source SMALLINT NOT NULL DEFAULT 1;
ALTER TABLE dt_detail ADD COLUMN src_detail VARCHAR(256);
ALTER TABLE dt_detail ADD COLUMN create_date DATE;
ALTER TABLE dt_detail ADD COLUMN update_date DATETIME;

-- A10 主力资金流
ALTER TABLE main_fund_flow ADD COLUMN data_source SMALLINT NOT NULL DEFAULT 1;
ALTER TABLE main_fund_flow ADD COLUMN src_detail VARCHAR(256);
ALTER TABLE main_fund_flow ADD COLUMN create_date DATE;
ALTER TABLE main_fund_flow ADD COLUMN update_date DATETIME;

-- A11 北向资金
ALTER TABLE northbound_flow ADD COLUMN data_source SMALLINT NOT NULL DEFAULT 1;
ALTER TABLE northbound_flow ADD COLUMN src_detail VARCHAR(256);
ALTER TABLE northbound_flow ADD COLUMN create_date DATE;
ALTER TABLE northbound_flow ADD COLUMN update_date DATETIME;

-- ============================================================================
-- 三、board_daily 加 board_type（板块类型：1地域 2行业 3概念）
-- ============================================================================
ALTER TABLE board_daily ADD COLUMN board_type SMALLINT;

-- ============================================================================
-- 四、stock_board_rel 加 board_type（板块类型：1地域 2行业 3概念）
-- ============================================================================
ALTER TABLE stock_board_rel ADD COLUMN board_type SMALLINT;

-- ============================================================================
-- 五、stock_daily 加实测 clist 新增字段
-- ============================================================================
ALTER TABLE stock_daily ADD COLUMN chg_amount NUMERIC(12,4);     -- f4 涨跌额
ALTER TABLE stock_daily ADD COLUMN amplitude NUMERIC(10,4);      -- f7 振幅%
ALTER TABLE stock_daily ADD COLUMN volume_ratio NUMERIC(10,4);  -- f10 量比
ALTER TABLE stock_daily ADD COLUMN avg_price NUMERIC(12,4);     -- f11 均价
ALTER TABLE stock_daily ADD COLUMN main_net NUMERIC(24,2);      -- f62 主力净流入
ALTER TABLE stock_daily ADD COLUMN pe_static NUMERIC(12,4);     -- f115 静态市盈率
ALTER TABLE stock_daily ADD COLUMN leader_code VARCHAR(16);     -- f128 领涨股代码
ALTER TABLE stock_daily ADD COLUMN industry_code VARCHAR(16);   -- f140 所属行业代码
ALTER TABLE stock_daily ADD COLUMN concept_code VARCHAR(16);    -- f141 所属概念代码
ALTER TABLE stock_daily ADD COLUMN market_code SMALLINT;        -- f152 市场码
ALTER TABLE stock_daily ADD COLUMN reserved_f24 NUMERIC(10,4);  -- f24 待确认
ALTER TABLE stock_daily ADD COLUMN reserved_f25 NUMERIC(10,4);  -- f25 待确认
ALTER TABLE stock_daily ADD COLUMN reserved_f107 NUMERIC(10,4); -- f107 待确认
ALTER TABLE stock_daily ADD COLUMN reserved_f136 NUMERIC(24,2); -- f136 待确认
ALTER TABLE stock_daily ADD COLUMN reserved_f173 NUMERIC(10,4); -- f173 待确认

-- ============================================================================
-- 六、stock_weekly 加实测 clist 新增字段
-- ============================================================================
ALTER TABLE stock_weekly ADD COLUMN chg_amount NUMERIC(12,4);
ALTER TABLE stock_weekly ADD COLUMN amplitude NUMERIC(10,4);
ALTER TABLE stock_weekly ADD COLUMN volume_ratio NUMERIC(10,4);
ALTER TABLE stock_weekly ADD COLUMN avg_price NUMERIC(12,4);
ALTER TABLE stock_weekly ADD COLUMN main_net NUMERIC(24,2);
ALTER TABLE stock_weekly ADD COLUMN pe_static NUMERIC(12,4);
ALTER TABLE stock_weekly ADD COLUMN leader_code VARCHAR(16);
ALTER TABLE stock_weekly ADD COLUMN industry_code VARCHAR(16);
ALTER TABLE stock_weekly ADD COLUMN concept_code VARCHAR(16);
ALTER TABLE stock_weekly ADD COLUMN market_code SMALLINT;

-- ============================================================================
-- 七、board_daily 加实测 clist.board 新增字段
-- ============================================================================
ALTER TABLE board_daily ADD COLUMN main_net NUMERIC(24,2);      -- f62 主力净流入
ALTER TABLE board_daily ADD COLUMN board_code2 VARCHAR(16);     -- f140 行业代码

-- ============================================================================
-- 八、limit_pool 加实测 push2ex 新增字段
-- ============================================================================
ALTER TABLE limit_pool ADD COLUMN amount NUMERIC(24,2);         -- 成交额
ALTER TABLE limit_pool ADD COLUMN fund NUMERIC(24,2);           -- 封单资金
ALTER TABLE limit_pool ADD COLUMN ltsz NUMERIC(24,2);           -- 流通市值
ALTER TABLE limit_pool ADD COLUMN tshare NUMERIC(24,2);         -- 总股本
ALTER TABLE limit_pool ADD COLUMN zf NUMERIC(10,4);             -- 涨幅%(炸板)
ALTER TABLE limit_pool ADD COLUMN zs NUMERIC(10,4);             -- 振幅%(炸板)
ALTER TABLE limit_pool ADD COLUMN ztp NUMERIC(12,4);            -- 涨停价
ALTER TABLE limit_pool ADD COLUMN zttj_ct INTEGER;               -- 连板统计-连板数
ALTER TABLE limit_pool ADD COLUMN zttj_days INTEGER;             -- 连板统计-天数
ALTER TABLE limit_pool ADD COLUMN lb INTEGER;                   -- 连板数(强势池)
ALTER TABLE limit_pool ADD COLUMN nh INTEGER;                   -- N日新高
ALTER TABLE limit_pool ADD COLUMN ztf VARCHAR(16);              -- 涨停封单描述
ALTER TABLE limit_pool ADD COLUMN ipod DATE;                    -- 上市日期(次新)
ALTER TABLE limit_pool ADD COLUMN o NUMERIC(12,4);              -- 开盘价(次新)
ALTER TABLE limit_pool ADD COLUMN od INTEGER;                   -- 上市天数
ALTER TABLE limit_pool ADD COLUMN ods INTEGER;                  -- 上市天数

-- ============================================================================
-- 九、strong_pool 加实测 push2ex 新增字段
-- ============================================================================
ALTER TABLE strong_pool ADD COLUMN amount NUMERIC(24,2);
ALTER TABLE strong_pool ADD COLUMN pct_chg NUMERIC(10,4);
ALTER TABLE strong_pool ADD COLUMN ltsz NUMERIC(24,2);
ALTER TABLE strong_pool ADD COLUMN tshare NUMERIC(24,2);
ALTER TABLE strong_pool ADD COLUMN zs NUMERIC(10,4);
ALTER TABLE strong_pool ADD COLUMN ztp NUMERIC(12,4);
ALTER TABLE strong_pool ADD COLUMN lb INTEGER;
ALTER TABLE strong_pool ADD COLUMN nh INTEGER;
ALTER TABLE strong_pool ADD COLUMN ztf VARCHAR(16);
ALTER TABLE strong_pool ADD COLUMN zttj_ct INTEGER;
ALTER TABLE strong_pool ADD COLUMN zttj_days INTEGER;
ALTER TABLE strong_pool ADD COLUMN board_code VARCHAR(16);

-- ============================================================================
-- 十、dragon_tiger 加实测 datacenter 新增字段
-- ============================================================================
ALTER TABLE dragon_tiger ADD COLUMN billboard_deal_amt NUMERIC(24,2);
ALTER TABLE dragon_tiger ADD COLUMN accum_amount NUMERIC(24,2);
ALTER TABLE dragon_tiger ADD COLUMN buy_ratio NUMERIC(10,4);
ALTER TABLE dragon_tiger ADD COLUMN sell_ratio NUMERIC(10,4);
ALTER TABLE dragon_tiger ADD COLUMN buy_seat INTEGER;
ALTER TABLE dragon_tiger ADD COLUMN sell_seat INTEGER;
ALTER TABLE dragon_tiger ADD COLUMN buy_seat_new INTEGER;
ALTER TABLE dragon_tiger ADD COLUMN sell_seat_new INTEGER;
ALTER TABLE dragon_tiger ADD COLUMN change_rate NUMERIC(10,4);
ALTER TABLE dragon_tiger ADD COLUMN close_price NUMERIC(12,4);
ALTER TABLE dragon_tiger ADD COLUMN turnoverrate NUMERIC(10,4);
ALTER TABLE dragon_tiger ADD COLUMN free_market_cap NUMERIC(24,2);
ALTER TABLE dragon_tiger ADD COLUMN market VARCHAR(8);
ALTER TABLE dragon_tiger ADD COLUMN deal_amount_ratio NUMERIC(10,4);
ALTER TABLE dragon_tiger ADD COLUMN deal_net_ratio NUMERIC(10,4);
ALTER TABLE dragon_tiger ADD COLUMN security_inner_code VARCHAR(16);
ALTER TABLE dragon_tiger ADD COLUMN security_type_code VARCHAR(8);
ALTER TABLE dragon_tiger ADD COLUMN trade_id BIGINT;
ALTER TABLE dragon_tiger ADD COLUMN trade_market VARCHAR(16);
ALTER TABLE dragon_tiger ADD COLUMN trade_market_code VARCHAR(8);
