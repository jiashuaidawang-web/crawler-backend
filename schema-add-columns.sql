-- ============================================================================
-- 补充缺失字段
-- ============================================================================

-- stock_weekly 加 stock_name
DO $$
BEGIN
  IF NOT EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema='public' AND table_name='stock_weekly' AND column_name='stock_name') THEN
    ALTER TABLE stock_weekly ADD COLUMN stock_name VARCHAR(64);
  END IF;
END $$;

-- trade_log 加 ying_dai（应对）
DO $$
BEGIN
  IF NOT EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema='public' AND table_name='trade_log' AND column_name='ying_dai') THEN
    ALTER TABLE trade_log ADD COLUMN ying_dai VARCHAR(16);
  END IF;
END $$;

-- 注释
COMMENT ON COLUMN stock_weekly.stock_name IS '股票名称';
COMMENT ON COLUMN trade_log.ying_dai IS '买对/买错/未明 三态处置';
