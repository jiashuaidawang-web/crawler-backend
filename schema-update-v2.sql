-- ============================================================================
-- 股票复盘系统 · 数据模型更新（OpenGauss/PostgreSQL 兼容版 2026-08-01）
-- 运行：psql -h 100.92.86.64 -p 15432 -U dbuser -d postgres -f schema-update-v2.sql
-- ============================================================================

-- ============================================================================
-- 一、新建 board_basic 表（板块基础数据）
-- ============================================================================
CREATE TABLE IF NOT EXISTS board_basic (
  id           BIGSERIAL PRIMARY KEY,
  board_type   SMALLINT NOT NULL,             -- 1：地域 2：行业 3：概念
  code         VARCHAR(16),                   -- 同花顺板块指数代码
  board_code   VARCHAR(16) NOT NULL,          -- 板块代号(如 BK0450)
  board_name   VARCHAR(64) NOT NULL,          -- 板块名称
  features     VARCHAR(340),                  -- 备用字段
  status       SMALLINT DEFAULT 1,             -- 1=正常 0=删除
  data_source  SMALLINT NOT NULL DEFAULT 0,   -- 0=东财 1=同花顺
  create_date  DATE NOT NULL DEFAULT CURRENT_DATE,
  update_date  TIMESTAMP
);
COMMENT ON TABLE board_basic IS '板块基础数据表；存储板块基本信息，用于增量分析和概念股票归类';

-- ============================================================================
-- 二、已存在表加基础字段（data_source, src_detail, create_date, update_date）
-- ============================================================================
DO $$
DECLARE
  t TEXT;
BEGIN
  FOREACH t IN ARRAY ARRAY[
    'stock_daily','stock_weekly','index_daily','limit_pool','strong_pool',
    'board_daily','stock_board_rel','dragon_tiger','dt_detail',
    'main_fund_flow','northbound_flow','news_event','concept','financial',
    'trade_log','trade_calendar'
  ] LOOP
    IF NOT EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema='public' AND table_name=t AND column_name='data_source') THEN
      EXECUTE format('ALTER TABLE %I ADD COLUMN data_source SMALLINT NOT NULL DEFAULT 0', t);
    END IF;
    IF NOT EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema='public' AND table_name=t AND column_name='src_detail') THEN
      EXECUTE format('ALTER TABLE %I ADD COLUMN src_detail VARCHAR(256)', t);
    END IF;
    IF NOT EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema='public' AND table_name=t AND column_name='create_date') THEN
      EXECUTE format('ALTER TABLE %I ADD COLUMN create_date DATE DEFAULT CURRENT_DATE', t);
    END IF;
    IF NOT EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema='public' AND table_name=t AND column_name='update_date') THEN
      EXECUTE format('ALTER TABLE %I ADD COLUMN update_date TIMESTAMP', t);
    END IF;
  END LOOP;
END $$;

-- ============================================================================
-- 三、board_daily 加 board_type（板块类型：1地域 2行业 3概念）
-- ============================================================================
DO $$
BEGIN
  IF NOT EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema='public' AND table_name='board_daily' AND column_name='board_type') THEN
    ALTER TABLE board_daily ADD COLUMN board_type SMALLINT;
  END IF;
END $$;

-- ============================================================================
-- 四、stock_board_rel 加 board_type（板块类型：1地域 2行业 3概念）
-- ============================================================================
DO $$
BEGIN
  IF NOT EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema='public' AND table_name='stock_board_rel' AND column_name='board_type') THEN
    ALTER TABLE stock_board_rel ADD COLUMN board_type SMALLINT;
  END IF;
END $$;

-- ============================================================================
-- 五、stock_daily 加实测 clist 新增字段
-- ============================================================================
DO $$
BEGIN
  IF NOT EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema='public' AND table_name='stock_daily' AND column_name='chg_amount') THEN ALTER TABLE stock_daily ADD COLUMN chg_amount NUMERIC(12,4); END IF;
  IF NOT EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema='public' AND table_name='stock_daily' AND column_name='amplitude') THEN ALTER TABLE stock_daily ADD COLUMN amplitude NUMERIC(10,4); END IF;
  IF NOT EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema='public' AND table_name='stock_daily' AND column_name='volume_ratio') THEN ALTER TABLE stock_daily ADD COLUMN volume_ratio NUMERIC(10,4); END IF;
  IF NOT EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema='public' AND table_name='stock_daily' AND column_name='avg_price') THEN ALTER TABLE stock_daily ADD COLUMN avg_price NUMERIC(12,4); END IF;
  IF NOT EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema='public' AND table_name='stock_daily' AND column_name='main_net') THEN ALTER TABLE stock_daily ADD COLUMN main_net NUMERIC(24,2); END IF;
  IF NOT EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema='public' AND table_name='stock_daily' AND column_name='pe_static') THEN ALTER TABLE stock_daily ADD COLUMN pe_static NUMERIC(12,4); END IF;
  IF NOT EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema='public' AND table_name='stock_daily' AND column_name='leader_code') THEN ALTER TABLE stock_daily ADD COLUMN leader_code VARCHAR(16); END IF;
  IF NOT EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema='public' AND table_name='stock_daily' AND column_name='industry_code') THEN ALTER TABLE stock_daily ADD COLUMN industry_code VARCHAR(16); END IF;
  IF NOT EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema='public' AND table_name='stock_daily' AND column_name='concept_code') THEN ALTER TABLE stock_daily ADD COLUMN concept_code VARCHAR(16); END IF;
  IF NOT EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema='public' AND table_name='stock_daily' AND column_name='market_code') THEN ALTER TABLE stock_daily ADD COLUMN market_code SMALLINT; END IF;
  IF NOT EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema='public' AND table_name='stock_daily' AND column_name='reserved_f24') THEN ALTER TABLE stock_daily ADD COLUMN reserved_f24 NUMERIC(10,4); END IF;
  IF NOT EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema='public' AND table_name='stock_daily' AND column_name='reserved_f25') THEN ALTER TABLE stock_daily ADD COLUMN reserved_f25 NUMERIC(10,4); END IF;
  IF NOT EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema='public' AND table_name='stock_daily' AND column_name='reserved_f107') THEN ALTER TABLE stock_daily ADD COLUMN reserved_f107 NUMERIC(10,4); END IF;
  IF NOT EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema='public' AND table_name='stock_daily' AND column_name='reserved_f136') THEN ALTER TABLE stock_daily ADD COLUMN reserved_f136 NUMERIC(24,2); END IF;
  IF NOT EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema='public' AND table_name='stock_daily' AND column_name='reserved_f173') THEN ALTER TABLE stock_daily ADD COLUMN reserved_f173 NUMERIC(10,4); END IF;
END $$;

-- ============================================================================
-- 六、stock_weekly 加实测 clist 新增字段
-- ============================================================================
DO $$
BEGIN
  IF NOT EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema='public' AND table_name='stock_weekly' AND column_name='chg_amount') THEN ALTER TABLE stock_weekly ADD COLUMN chg_amount NUMERIC(12,4); END IF;
  IF NOT EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema='public' AND table_name='stock_weekly' AND column_name='amplitude') THEN ALTER TABLE stock_weekly ADD COLUMN amplitude NUMERIC(10,4); END IF;
  IF NOT EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema='public' AND table_name='stock_weekly' AND column_name='volume_ratio') THEN ALTER TABLE stock_weekly ADD COLUMN volume_ratio NUMERIC(10,4); END IF;
  IF NOT EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema='public' AND table_name='stock_weekly' AND column_name='avg_price') THEN ALTER TABLE stock_weekly ADD COLUMN avg_price NUMERIC(12,4); END IF;
  IF NOT EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema='public' AND table_name='stock_weekly' AND column_name='main_net') THEN ALTER TABLE stock_weekly ADD COLUMN main_net NUMERIC(24,2); END IF;
  IF NOT EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema='public' AND table_name='stock_weekly' AND column_name='pe_static') THEN ALTER TABLE stock_weekly ADD COLUMN pe_static NUMERIC(12,4); END IF;
  IF NOT EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema='public' AND table_name='stock_weekly' AND column_name='leader_code') THEN ALTER TABLE stock_weekly ADD COLUMN leader_code VARCHAR(16); END IF;
  IF NOT EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema='public' AND table_name='stock_weekly' AND column_name='industry_code') THEN ALTER TABLE stock_weekly ADD COLUMN industry_code VARCHAR(16); END IF;
  IF NOT EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema='public' AND table_name='stock_weekly' AND column_name='concept_code') THEN ALTER TABLE stock_weekly ADD COLUMN concept_code VARCHAR(16); END IF;
  IF NOT EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema='public' AND table_name='stock_weekly' AND column_name='market_code') THEN ALTER TABLE stock_weekly ADD COLUMN market_code SMALLINT; END IF;
END $$;

-- ============================================================================
-- 七、board_daily 加实测 clist.board 新增字段
-- ============================================================================
DO $$
BEGIN
  IF NOT EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema='public' AND table_name='board_daily' AND column_name='main_net') THEN ALTER TABLE board_daily ADD COLUMN main_net NUMERIC(24,2); END IF;
  IF NOT EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema='public' AND table_name='board_daily' AND column_name='board_code2') THEN ALTER TABLE board_daily ADD COLUMN board_code2 VARCHAR(16); END IF;
END $$;

-- ============================================================================
-- 八、limit_pool 加实测 push2ex 新增字段
-- ============================================================================
DO $$
BEGIN
  IF NOT EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema='public' AND table_name='limit_pool' AND column_name='amount') THEN ALTER TABLE limit_pool ADD COLUMN amount NUMERIC(24,2); END IF;
  IF NOT EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema='public' AND table_name='limit_pool' AND column_name='fund') THEN ALTER TABLE limit_pool ADD COLUMN fund NUMERIC(24,2); END IF;
  IF NOT EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema='public' AND table_name='limit_pool' AND column_name='ltsz') THEN ALTER TABLE limit_pool ADD COLUMN ltsz NUMERIC(24,2); END IF;
  IF NOT EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema='public' AND table_name='limit_pool' AND column_name='tshare') THEN ALTER TABLE limit_pool ADD COLUMN tshare NUMERIC(24,2); END IF;
  IF NOT EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema='public' AND table_name='limit_pool' AND column_name='zf') THEN ALTER TABLE limit_pool ADD COLUMN zf NUMERIC(10,4); END IF;
  IF NOT EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema='public' AND table_name='limit_pool' AND column_name='zs') THEN ALTER TABLE limit_pool ADD COLUMN zs NUMERIC(10,4); END IF;
  IF NOT EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema='public' AND table_name='limit_pool' AND column_name='ztp') THEN ALTER TABLE limit_pool ADD COLUMN ztp NUMERIC(12,4); END IF;
  IF NOT EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema='public' AND table_name='limit_pool' AND column_name='zttj_ct') THEN ALTER TABLE limit_pool ADD COLUMN zttj_ct INTEGER; END IF;
  IF NOT EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema='public' AND table_name='limit_pool' AND column_name='zttj_days') THEN ALTER TABLE limit_pool ADD COLUMN zttj_days INTEGER; END IF;
  IF NOT EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema='public' AND table_name='limit_pool' AND column_name='lb') THEN ALTER TABLE limit_pool ADD COLUMN lb INTEGER; END IF;
  IF NOT EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema='public' AND table_name='limit_pool' AND column_name='nh') THEN ALTER TABLE limit_pool ADD COLUMN nh INTEGER; END IF;
  IF NOT EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema='public' AND table_name='limit_pool' AND column_name='ztf') THEN ALTER TABLE limit_pool ADD COLUMN ztf VARCHAR(16); END IF;
  IF NOT EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema='public' AND table_name='limit_pool' AND column_name='ipod') THEN ALTER TABLE limit_pool ADD COLUMN ipod DATE; END IF;
  IF NOT EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema='public' AND table_name='limit_pool' AND column_name='o') THEN ALTER TABLE limit_pool ADD COLUMN o NUMERIC(12,4); END IF;
  IF NOT EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema='public' AND table_name='limit_pool' AND column_name='od') THEN ALTER TABLE limit_pool ADD COLUMN od INTEGER; END IF;
  IF NOT EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema='public' AND table_name='limit_pool' AND column_name='ods') THEN ALTER TABLE limit_pool ADD COLUMN ods INTEGER; END IF;
END $$;

-- ============================================================================
-- 九、strong_pool 加实测 push2ex 新增字段
-- ============================================================================
DO $$
BEGIN
  IF NOT EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema='public' AND table_name='strong_pool' AND column_name='amount') THEN ALTER TABLE strong_pool ADD COLUMN amount NUMERIC(24,2); END IF;
  IF NOT EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema='public' AND table_name='strong_pool' AND column_name='pct_chg') THEN ALTER TABLE strong_pool ADD COLUMN pct_chg NUMERIC(10,4); END IF;
  IF NOT EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema='public' AND table_name='strong_pool' AND column_name='ltsz') THEN ALTER TABLE strong_pool ADD COLUMN ltsz NUMERIC(24,2); END IF;
  IF NOT EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema='public' AND table_name='strong_pool' AND column_name='tshare') THEN ALTER TABLE strong_pool ADD COLUMN tshare NUMERIC(24,2); END IF;
  IF NOT EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema='public' AND table_name='strong_pool' AND column_name='zs') THEN ALTER TABLE strong_pool ADD COLUMN zs NUMERIC(10,4); END IF;
  IF NOT EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema='public' AND table_name='strong_pool' AND column_name='ztp') THEN ALTER TABLE strong_pool ADD COLUMN ztp NUMERIC(12,4); END IF;
  IF NOT EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema='public' AND table_name='strong_pool' AND column_name='lb') THEN ALTER TABLE strong_pool ADD COLUMN lb INTEGER; END IF;
  IF NOT EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema='public' AND table_name='strong_pool' AND column_name='nh') THEN ALTER TABLE strong_pool ADD COLUMN nh INTEGER; END IF;
  IF NOT EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema='public' AND table_name='strong_pool' AND column_name='ztf') THEN ALTER TABLE strong_pool ADD COLUMN ztf VARCHAR(16); END IF;
  IF NOT EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema='public' AND table_name='strong_pool' AND column_name='zttj_ct') THEN ALTER TABLE strong_pool ADD COLUMN zttj_ct INTEGER; END IF;
  IF NOT EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema='public' AND table_name='strong_pool' AND column_name='zttj_days') THEN ALTER TABLE strong_pool ADD COLUMN zttj_days INTEGER; END IF;
  IF NOT EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema='public' AND table_name='strong_pool' AND column_name='board_code') THEN ALTER TABLE strong_pool ADD COLUMN board_code VARCHAR(16); END IF;
END $$;

-- ============================================================================
-- 十、dragon_tiger 加实测 datacenter 新增字段
-- ============================================================================
DO $$
BEGIN
  IF NOT EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema='public' AND table_name='dragon_tiger' AND column_name='billboard_deal_amt') THEN ALTER TABLE dragon_tiger ADD COLUMN billboard_deal_amt NUMERIC(24,2); END IF;
  IF NOT EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema='public' AND table_name='dragon_tiger' AND column_name='accum_amount') THEN ALTER TABLE dragon_tiger ADD COLUMN accum_amount NUMERIC(24,2); END IF;
  IF NOT EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema='public' AND table_name='dragon_tiger' AND column_name='buy_ratio') THEN ALTER TABLE dragon_tiger ADD COLUMN buy_ratio NUMERIC(10,4); END IF;
  IF NOT EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema='public' AND table_name='dragon_tiger' AND column_name='sell_ratio') THEN ALTER TABLE dragon_tiger ADD COLUMN sell_ratio NUMERIC(10,4); END IF;
  IF NOT EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema='public' AND table_name='dragon_tiger' AND column_name='buy_seat') THEN ALTER TABLE dragon_tiger ADD COLUMN buy_seat INTEGER; END IF;
  IF NOT EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema='public' AND table_name='dragon_tiger' AND column_name='sell_seat') THEN ALTER TABLE dragon_tiger ADD COLUMN sell_seat INTEGER; END IF;
  IF NOT EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema='public' AND table_name='dragon_tiger' AND column_name='buy_seat_new') THEN ALTER TABLE dragon_tiger ADD COLUMN buy_seat_new INTEGER; END IF;
  IF NOT EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema='public' AND table_name='dragon_tiger' AND column_name='sell_seat_new') THEN ALTER TABLE dragon_tiger ADD COLUMN sell_seat_new INTEGER; END IF;
  IF NOT EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema='public' AND table_name='dragon_tiger' AND column_name='change_rate') THEN ALTER TABLE dragon_tiger ADD COLUMN change_rate NUMERIC(10,4); END IF;
  IF NOT EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema='public' AND table_name='dragon_tiger' AND column_name='close_price') THEN ALTER TABLE dragon_tiger ADD COLUMN close_price NUMERIC(12,4); END IF;
  IF NOT EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema='public' AND table_name='dragon_tiger' AND column_name='turnoverrate') THEN ALTER TABLE dragon_tiger ADD COLUMN turnoverrate NUMERIC(10,4); END IF;
  IF NOT EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema='public' AND table_name='dragon_tiger' AND column_name='free_market_cap') THEN ALTER TABLE dragon_tiger ADD COLUMN free_market_cap NUMERIC(24,2); END IF;
  IF NOT EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema='public' AND table_name='dragon_tiger' AND column_name='market') THEN ALTER TABLE dragon_tiger ADD COLUMN market VARCHAR(8); END IF;
  IF NOT EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema='public' AND table_name='dragon_tiger' AND column_name='deal_amount_ratio') THEN ALTER TABLE dragon_tiger ADD COLUMN deal_amount_ratio NUMERIC(10,4); END IF;
  IF NOT EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema='public' AND table_name='dragon_tiger' AND column_name='deal_net_ratio') THEN ALTER TABLE dragon_tiger ADD COLUMN deal_net_ratio NUMERIC(10,4); END IF;
  IF NOT EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema='public' AND table_name='dragon_tiger' AND column_name='security_inner_code') THEN ALTER TABLE dragon_tiger ADD COLUMN security_inner_code VARCHAR(16); END IF;
  IF NOT EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema='public' AND table_name='dragon_tiger' AND column_name='security_type_code') THEN ALTER TABLE dragon_tiger ADD COLUMN security_type_code VARCHAR(8); END IF;
  IF NOT EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema='public' AND table_name='dragon_tiger' AND column_name='trade_id') THEN ALTER TABLE dragon_tiger ADD COLUMN trade_id BIGINT; END IF;
  IF NOT EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema='public' AND table_name='dragon_tiger' AND column_name='trade_market') THEN ALTER TABLE dragon_tiger ADD COLUMN trade_market VARCHAR(16); END IF;
  IF NOT EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema='public' AND table_name='dragon_tiger' AND column_name='trade_market_code') THEN ALTER TABLE dragon_tiger ADD COLUMN trade_market_code VARCHAR(8); END IF;
END $$;
