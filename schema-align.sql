-- ============================================================================
-- 数据库 vs 实体类 对齐 SQL（基于实际 SELECT 结果）
-- 运行：psql -h 100.92.86.64 -p 15432 -U dbuser -d postgres -f schema-align.sql
-- ============================================================================

-- ============================================================================
-- 一、limit_pool：删除多余的 limit_type（用 type 代替）
-- ============================================================================
ALTER TABLE limit_pool DROP COLUMN IF EXISTS limit_type;

-- ============================================================================
-- 二、stock_daily：删除重复的 f* 前缀列（实体类用驼峰名，不是 f* 前缀）
-- 数据库同时存在 f4_chg_amount 和 chg_amount，只保留 chg_amount
-- ============================================================================
ALTER TABLE stock_daily DROP COLUMN IF EXISTS f4_chg_amount;
ALTER TABLE stock_daily DROP COLUMN IF EXISTS f7_amplitude;
ALTER TABLE stock_daily DROP COLUMN IF EXISTS f8_turnover_rate;
ALTER TABLE stock_daily DROP COLUMN IF EXISTS f10_volume_ratio;
ALTER TABLE stock_daily DROP COLUMN IF EXISTS f11_avg_price;
ALTER TABLE stock_daily DROP COLUMN IF EXISTS f62_main_net;
ALTER TABLE stock_daily DROP COLUMN IF EXISTS f115_pe_static;
ALTER TABLE stock_daily DROP COLUMN IF EXISTS f128_leader_code;
ALTER TABLE stock_daily DROP COLUMN IF EXISTS f136_unknown;
ALTER TABLE stock_daily DROP COLUMN IF EXISTS f140_board_code;
ALTER TABLE stock_daily DROP COLUMN IF EXISTS f141_board_code2;
ALTER TABLE stock_daily DROP COLUMN IF EXISTS f152_market_code;
ALTER TABLE stock_daily DROP COLUMN IF EXISTS f173_unknown;

-- ============================================================================
-- 三、stock_weekly：删除重复的 f* 前缀列
-- ============================================================================
ALTER TABLE stock_weekly DROP COLUMN IF EXISTS f4_chg_amount;
ALTER TABLE stock_weekly DROP COLUMN IF EXISTS f7_amplitude;
ALTER TABLE stock_weekly DROP COLUMN IF EXISTS f8_turnover_rate;
ALTER TABLE stock_weekly DROP COLUMN IF EXISTS f10_volume_ratio;
ALTER TABLE stock_weekly DROP COLUMN IF EXISTS f11_avg_price;
ALTER TABLE stock_weekly DROP COLUMN IF EXISTS f62_main_net;
ALTER TABLE stock_weekly DROP COLUMN IF EXISTS f115_pe_static;
ALTER TABLE stock_weekly DROP COLUMN IF EXISTS f128_leader_code;
ALTER TABLE stock_weekly DROP COLUMN IF EXISTS f136_unknown;
ALTER TABLE stock_weekly DROP COLUMN IF EXISTS f140_board_code;
ALTER TABLE stock_weekly DROP COLUMN IF EXISTS f141_board_code2;
ALTER TABLE stock_weekly DROP COLUMN IF EXISTS f152_market_code;
ALTER TABLE stock_weekly DROP COLUMN IF EXISTS f173_unknown;

-- ============================================================================
-- 四、board_daily：删除重复的 f* 前缀列
-- ============================================================================
ALTER TABLE board_daily DROP COLUMN IF EXISTS f62_main_net;
ALTER TABLE board_daily DROP COLUMN IF EXISTS f107_unknown;
ALTER TABLE board_daily DROP COLUMN IF EXISTS f124_timestamp;
ALTER TABLE board_daily DROP COLUMN IF EXISTS f140_board_code;
ALTER TABLE board_daily DROP COLUMN IF EXISTS f141_board_code2;
ALTER TABLE board_daily DROP COLUMN IF EXISTS f207_unknown;
ALTER TABLE board_daily DROP COLUMN IF EXISTS f208_unknown;
ALTER TABLE board_daily DROP COLUMN IF EXISTS f209_unknown;
ALTER TABLE board_daily DROP COLUMN IF EXISTS f222_unknown;

-- ============================================================================
-- 五、trade_log：删除 应对（用 ying_dai 代替）
-- ============================================================================
ALTER TABLE trade_log DROP COLUMN IF EXISTS 应对;
