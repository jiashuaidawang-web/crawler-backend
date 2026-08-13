-- ============================================================================
-- 修复 dragon_tiger：ORDER BY 补上 reason，杜绝 ReplacingMergeTree 吞行
--
-- 根因：线上 ORDER BY (ts_code, trade_date) 缺 reason，同票同天多原因上榜行被静默折叠
--       （ClickHouse 限制：MODIFY ORDER BY 不能追加列，必须重建表）。
--
-- 步骤：建正确结构的新表 → 重命名切换（原子） → 拷数据 → 验证 → 手动删旧表。
-- 注意：旧表里已被吞的多 reason 行不会因此恢复，需重灌 2026-08-13 补回（见文末）。
--
-- 执行：clickhouse-client --host <ck> --port=8123 -d crawler < dragon_tiger_fix_orderby.sql
-- ============================================================================

-- 1. 建新表（结构与旧表完全一致，仅 ORDER BY 补上 reason；_ver 保持 MATERIALIZED 不变）
CREATE TABLE IF NOT EXISTS dragon_tiger_new (
    trade_date              Date          COMMENT '交易日期',
    ts_code                 String        COMMENT '股票代码(SECUCODE,带后缀)',
    stock_name              Nullable(String) COMMENT '股票名称(SECURITY_NAME_ABBR)',
    reason                  Nullable(String) COMMENT '上榜原因(EXPLAIN)',
    explanation             Nullable(String) COMMENT '上榜原因详(EXPLANATION)',
    abnormal_type           Nullable(String) COMMENT '变动类型(CHANGE_TYPE)',
    net_buy                 Nullable(Decimal(18, 2)) COMMENT '龙虎榜净买额(BILLBOARD_NET_AMT,元)',
    total_buy               Nullable(Decimal(18, 2)) COMMENT '买入金额(BILLBOARD_BUY_AMT,元)',
    total_sell              Nullable(Decimal(18, 2)) COMMENT '卖出金额(BILLBOARD_SELL_AMT,元)',
    billboard_deal_amt      Nullable(Decimal(18, 2)) COMMENT '龙虎榜成交额(BILLBOARD_DEAL_AMT,元)',
    accum_amount            Nullable(Decimal(18, 2)) COMMENT '累计成交额(ACCUM_AMOUNT,元)',
    buy_ratio               Nullable(Decimal(18, 4)) COMMENT '买入占比(BUY_RATIO,%)',
    sell_ratio              Nullable(Decimal(18, 4)) COMMENT '卖出占比(SELL_RATIO,%)',
    buy_seat                Nullable(Int32) COMMENT '买出席位数(BUY_SEAT)',
    sell_seat               Nullable(Int32) COMMENT '卖出席位数(SELL_SEAT)',
    buy_seat_new            Nullable(Int32) COMMENT '买出席位数(新口径,BUY_SEAT_NEW)',
    sell_seat_new           Nullable(Int32) COMMENT '卖出席位数(新口径,SELL_SEAT_NEW)',
    change_rate             Nullable(Decimal(18, 4)) COMMENT '涨跌幅(CHANGE_RATE,%)',
    close_price             Nullable(Decimal(18, 4)) COMMENT '收盘价(CLOSE_PRICE,元)',
    turnoverrate            Nullable(Decimal(18, 4)) COMMENT '换手率(TURNOVERRATE,%)',
    free_market_cap         Nullable(Decimal(18, 2)) COMMENT '流通市值(FREE_MARKET_CAP,元)',
    market                  Nullable(String) COMMENT '市场(MARKET): SZ/BJ/SH',
    deal_amount_ratio       Nullable(Decimal(18, 4)) COMMENT '龙虎榜成交额占市场成交额比(DEAL_AMOUNT_RATIO,%)',
    deal_net_ratio          Nullable(Decimal(18, 4)) COMMENT '龙虎榜净买额占市场成交额比(DEAL_NET_RATIO,%)',
    security_inner_code     Nullable(String) COMMENT '证券内部编码(SECURITY_INNER_CODE)',
    security_type_code      Nullable(String) COMMENT '证券类型编码(SECURITY_TYPE_CODE)',
    trade_id                Nullable(Int64) COMMENT '交易ID(TRADE_ID,关联主表)',
    trade_market            Nullable(String) COMMENT '交易市场(TRADE_MARKET)',
    trade_market_code       Nullable(String) COMMENT '交易市场编码(TRADE_MARKET_CODE)',
    net_bs_amt              Nullable(Decimal(18, 2)) COMMENT '龙虎榜净买卖额(另一口径,NET_BS_AMT,元)',
    sum_buy_amt             Nullable(Decimal(18, 2)) COMMENT '买入总额(含非龙虎榜部分,SUM_BUY_AMT,元)',
    sum_sell_amt            Nullable(Decimal(18, 2)) COMMENT '卖出总额(SUM_SELL_AMT,元)',
    d1_close_adjchrate      Nullable(Decimal(18, 4)) COMMENT '上榜后1日复权涨跌幅(D1_CLOSE_ADJCHRATE,%)',
    d2_close_adjchrate      Nullable(Decimal(18, 4)) COMMENT '上榜后2日复权涨跌幅(D2_CLOSE_ADJCHRATE,%)',
    d5_close_adjchrate      Nullable(Decimal(18, 4)) COMMENT '上榜后5日复权涨跌幅(D5_CLOSE_ADJCHRATE,%)',
    d10_close_adjchrate     Nullable(Decimal(18, 4)) COMMENT '上榜后10日复权涨跌幅(D10_CLOSE_ADJCHRATE,%)',
    d20_close_adjchrate     Nullable(Decimal(18, 4)) COMMENT '上榜后20日复权涨跌幅(D20_CLOSE_ADJCHRATE,%)',
    d30_close_adjchrate     Nullable(Decimal(18, 4)) COMMENT '上榜后30日复权涨跌幅(D30_CLOSE_ADJCHRATE,%)',
    data_source             UInt8         COMMENT '数据来源: 0=东财 1=同花顺',
    src_detail              Nullable(String) COMMENT '来源URL/接口/备注',
    create_date             Nullable(Date) COMMENT '入库日期',
    update_date             Nullable(DateTime) COMMENT '更新时间',
    _ver                    DateTime MATERIALIZED coalesce(update_date, toDateTime(0))
) ENGINE = ReplacingMergeTree(_ver)
PARTITION BY toYYYYMM(trade_date)
ORDER BY (ts_code, trade_date, reason)
SETTINGS index_granularity = 8192
COMMENT 'A8 龙虎榜(S3 主力博弈) — ORDER BY 含 reason，避免多原因上榜行被折叠';

-- 2. 原子重命名切换：旧表 → dragon_tiger_old，新表 → dragon_tiger（秒级，服务短暂不可写）
RENAME TABLE dragon_tiger TO dragon_tiger_old, dragon_tiger_new TO dragon_tiger;

-- 3. 拷贝全部历史数据到新表（显式列，跳过 MATERIALIZED 的 _ver，由其自动计算）
INSERT INTO dragon_tiger (
    trade_date, ts_code, stock_name, reason, explanation, abnormal_type,
    net_buy, total_buy, total_sell, billboard_deal_amt, accum_amount,
    buy_ratio, sell_ratio, buy_seat, sell_seat, buy_seat_new, sell_seat_new,
    change_rate, close_price, turnoverrate, free_market_cap, market,
    deal_amount_ratio, deal_net_ratio, security_inner_code, security_type_code,
    trade_id, trade_market, trade_market_code,
    net_bs_amt, sum_buy_amt, sum_sell_amt,
    d1_close_adjchrate, d2_close_adjchrate, d5_close_adjchrate,
    d10_close_adjchrate, d20_close_adjchrate, d30_close_adjchrate,
    data_source, src_detail, create_date, update_date
) SELECT
    trade_date, ts_code, stock_name, reason, explanation, abnormal_type,
    net_buy, total_buy, total_sell, billboard_deal_amt, accum_amount,
    buy_ratio, sell_ratio, buy_seat, sell_seat, buy_seat_new, sell_seat_new,
    change_rate, close_price, turnoverrate, free_market_cap, market,
    deal_amount_ratio, deal_net_ratio, security_inner_code, security_type_code,
    trade_id, trade_market, trade_market_code,
    net_bs_amt, sum_buy_amt, sum_sell_amt,
    d1_close_adjchrate, d2_close_adjchrate, d5_close_adjchrate,
    d10_close_adjchrate, d20_close_adjchrate, d30_close_adjchrate,
    data_source, src_detail, create_date, update_date
FROM dragon_tiger_old;

-- 4. 验证
-- 4a. 新表结构：sorting_key 应为 (ts_code, trade_date, reason)
SELECT engine, partition_key, sorting_key, primary_key
FROM system.tables WHERE database = 'crawler' AND name = 'dragon_tiger';

-- 4b. 行数一致（新旧对比，确认无丢行）
SELECT 'old' AS t, count() AS rows FROM dragon_tiger_old
UNION ALL
SELECT 'new' AS t, count() AS rows FROM dragon_tiger;

-- 4c. 强制合并新表，让 ReplacingMergeTree 按新键去重
OPTIMIZE TABLE dragon_tiger FINAL;

-- 4d. 合并后 2026-08-13 行数（此时尚未重灌，仍会是旧表折叠后的数字）
SELECT count() AS rows_0813_after_migrate
FROM dragon_tiger FINAL WHERE trade_date = '2026-08-13';

-- ============================================================================
-- 5.（手动）确认 4b 行数一致、4c/4d 无报错后，再删旧表释放空间：
--    DROP TABLE dragon_tiger_old;
--
-- 6. 重灌 2026-08-13 补回被吞的多原因行（新 dedupInBatch 会把 87 行压到 ~71 个不同原因）：
--    curl -X POST "http://localhost:8081/api/crawl/seed-dragon-tiger" \
--      -H "Content-Type: application/json" -d '{"tradeDate":"2026-08-13","source":1}'
--    重灌后再 OPTIMIZE TABLE dragon_tiger FINAL; 行数即回到 ~71。
-- ============================================================================
