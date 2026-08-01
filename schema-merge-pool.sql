-- ============================================================================
-- limit_pool 合并 strong_pool：加 type 字段 + 删除 strong_pool
-- ============================================================================

-- 1. limit_pool 加 type 字段（如果不存在）
DO $$
BEGIN
  IF NOT EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema='public' AND table_name='limit_pool' AND column_name='type') THEN
    ALTER TABLE limit_pool ADD COLUMN type VARCHAR(16);
  END IF;
END $$;

COMMENT ON COLUMN limit_pool.type IS '池类型：limit_up涨停 / limit_down跌停 / zhaban炸板 / strong强势 / cixin次新';

-- 2. 把 strong_pool 的数据迁移到 limit_pool（如果 strong_pool 表存在且不为空）
-- 使用 WHERE NOT EXISTS 避免冲突（openGauss 不支持 ON CONFLICT）
DO $$
BEGIN
  IF EXISTS (SELECT 1 FROM information_schema.tables WHERE table_schema='public' AND table_name='strong_pool') THEN
    INSERT INTO limit_pool (trade_date, ts_code, stock_name, type, change_pct, high_days, amount, pct_chg, ltsz, tshare, ztp, lb, nh, ztf, zttj_ct, zttj_days, board_code, data_source, src_detail, create_date, update_date)
    SELECT s.trade_date, s.ts_code, s.stock_name, 'strong', s.change_pct, s.high_days, s.amount, s.pct_chg, s.ltsz, s.tshare, s.ztp, s.lb, s.nh, s.ztf, s.zttj_ct, s.zttj_days, s.board_code, s.data_source, s.src_detail, s.create_date, s.update_date
    FROM strong_pool s
    WHERE NOT EXISTS (
      SELECT 1 FROM limit_pool l WHERE l.ts_code = s.ts_code AND l.trade_date = s.trade_date
    );
  END IF;
END $$;

-- 3. 删除 strong_pool 表
DROP TABLE IF EXISTS strong_pool;
