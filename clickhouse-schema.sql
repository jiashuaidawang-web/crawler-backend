-- ============================================================================
-- 股票复盘系统 · ClickHouse 完整建库 DDL
-- 生成时间：2026-08-05
-- 用途：ClickHouse 数据库 crawler 全部分析表一键重建（幂等）
-- 运行：clickhouse-client --host 127.0.0.1 --port=8123 -d crawler < clickhouse-schema.sql
--       或：cat clickhouse-schema.sql | clickhouse-client --host 127.0.0.1
--
-- 约定：
--   金额单位=元；幅度/涨跌=百分比数值；成交量=手
--   data_source: 0=东财 1=同花顺；board_type: 1地域 2行业 3概念
--   全部幂等（CREATE TABLE IF NOT EXISTS），可重复执行
--   无主键自增（CK 不支持）、无 FK、无 VARCHAR 长度、无 BOOLEAN(用 UInt8)
--
-- 引擎选择：
--   只追加行情/池子/资金流/龙虎榜 → MergeTree
--   需要覆盖的维表 → ReplacingMergeTree(_ver=data_source)，同键保留高 data_source 行
--   ORDER BY = 查询最高频过滤字段；PARTITION BY = toYYYYMM(trade_date)
-- ============================================================================

-- 时间类型：Date32（交易日）/ DateTime（带时间字段）
-- 金额 Decimal：金额 Decimal64(2)，百分比 Decimal64(4)，通用 Decimal64(4)

-- 需要先建库（按需取消下面注释）
-- CREATE DATABASE IF NOT EXISTS crawler;

-- ========== 一、行情核心表 ==========

-- 1. 个股日线（主键 ts_code + trade_date，查询最高频）
CREATE TABLE IF NOT EXISTS stock_daily (
    trade_date              Date NOT NULL,
    ts_code                 String NOT NULL,
    stock_name              Nullable(String),
    open                    Nullable(Decimal64(4)),
    high                    Nullable(Decimal64(4)),
    low                     Nullable(Decimal64(4)),
    close                   Nullable(Decimal64(4)),
    pre_close               Nullable(Decimal64(4)),
    pct_chg                 Nullable(Decimal64(4)),
    vol                     Nullable(Decimal64(4)),
    amount                  Nullable(Decimal64(4)),
    turnover                Nullable(Decimal64(4)),
    total_mv                Nullable(Decimal64(2)),
    circ_mv                 Nullable(Decimal64(2)),
    pe                      Nullable(Decimal64(4)),
    is_limit_up             Nullable(UInt8),
    is_limit_down           Nullable(UInt8),
    chg_amount              Nullable(Decimal64(4)),
    amplitude               Nullable(Decimal64(4)),
    volume_ratio            Nullable(Decimal64(4)),
    avg_price               Nullable(Decimal64(4)),
    main_net                Nullable(Decimal64(2)),
    pe_static               Nullable(Decimal64(4)),
    leader_code             Nullable(String),
    industry_code           Nullable(String),
    concept_code            Nullable(String),
    market_code             Nullable(Int32),
    velocity                Nullable(Decimal64(4)),
    is_new_high             Nullable(UInt8),
    chg_60d                 Nullable(Decimal64(4)),
    seal_fund               Nullable(Decimal64(2)),
    board_days              Nullable(Int32),
    board_stat              Nullable(String),
    first_seal_time         Nullable(String),
    last_seal_time          Nullable(String),
    limit_type              Nullable(Int32),
    reserved_f24            Nullable(Decimal64(4)),
    reserved_f25            Nullable(Decimal64(4)),
    reserved_f107           Nullable(Decimal64(4)),
    reserved_f136           Nullable(Decimal64(2)),
    reserved_f173           Nullable(Decimal64(4)),
    data_source             UInt8 NOT NULL DEFAULT 0,
    src_detail              Nullable(String),
    create_date             Nullable(Date),
    update_date             DateTime
) ENGINE = MergeTree()
PARTITION BY toYYYYMM(trade_date)
ORDER BY (ts_code, trade_date)
SETTINGS index_granularity = 8192;

-- 2. 个股周线
CREATE TABLE IF NOT EXISTS stock_weekly (
    trade_date              Date NOT NULL,
    ts_code                 String NOT NULL,
    stock_name              Nullable(String),
    open                    Nullable(Decimal64(4)),
    high                    Nullable(Decimal64(4)),
    low                     Nullable(Decimal64(4)),
    close                   Nullable(Decimal64(4)),
    vol                     Nullable(Decimal64(4)),
    amount                  Nullable(Decimal64(4)),
    chg_amount              Nullable(Decimal64(4)),
    amplitude               Nullable(Decimal64(4)),
    volume_ratio            Nullable(Decimal64(4)),
    avg_price               Nullable(Decimal64(4)),
    main_net                Nullable(Decimal64(2)),
    pe_static               Nullable(Decimal64(4)),
    leader_code             Nullable(String),
    industry_code           Nullable(String),
    concept_code            Nullable(String),
    market_code             Nullable(Int32),
    data_source             UInt8 NOT NULL DEFAULT 0,
    src_detail              Nullable(String),
    create_date             Nullable(Date),
    update_date             DateTime
) ENGINE = MergeTree()
PARTITION BY toYYYYMM(trade_date)
ORDER BY (ts_code, trade_date)
SETTINGS index_granularity = 8192;

-- 3. 指数日线
CREATE TABLE IF NOT EXISTS index_daily (
    trade_date              Date NOT NULL,
    index_code              String NOT NULL,
    index_name              Nullable(String),
    open                    Nullable(Decimal64(4)),
    high                    Nullable(Decimal64(4)),
    low                     Nullable(Decimal64(4)),
    close                   Nullable(Decimal64(4)),
    pre_close               Nullable(Decimal64(4)),
    pct_chg                 Nullable(Decimal64(4)),
    vol                     Nullable(Decimal64(4)),
    amount                  Nullable(Decimal64(4)),
    turnover                Nullable(Decimal64(4)),
    data_source             UInt8 NOT NULL DEFAULT 0,
    src_detail              Nullable(String),
    create_date             Nullable(Date),
    update_date             DateTime
) ENGINE = MergeTree()
PARTITION BY toYYYYMM(trade_date)
ORDER BY (index_code, trade_date)
SETTINGS index_granularity = 8192;

-- 4. 板块日线
CREATE TABLE IF NOT EXISTS board_daily (
    trade_date                  Date NOT NULL,
    board_code                  String NOT NULL,
    board_name              Nullable(String),
    board_type              Nullable(Int32),
    pct_chg                 Nullable(Decimal64(4)),
    amount                  Nullable(Decimal64(4)),
    up_count                Nullable(Int32),
    down_count              Nullable(Int32),
    limit_up_count          Nullable(Int32),
    leading_code            Nullable(String),
    leading_name            Nullable(String),
    main_net                Nullable(Decimal64(4)),
    board_code2             Nullable(String),
    price                   Nullable(Decimal64(4)),
    rise_fall               Nullable(Decimal64(4)),
    volume                  Nullable(Decimal64(4)),
    amplitude               Nullable(Decimal64(4)),
    high_price              Nullable(Decimal64(4)),
    low_price               Nullable(Decimal64(4)),
    today_open_price        Nullable(Decimal64(4)),
    yesterday_received_priceNullable(Decimal64(4)),
    volume_ratio            Nullable(Decimal64(4)),
    turnover_ratio          Nullable(Decimal64(4)),
    total_market_value      Nullable(Decimal64(2)),
    circulation_market_valueNullable(Decimal64(2)),
    data_source                 UInt8 NOT NULL DEFAULT 0,
    src_detail              Nullable(String),
    create_date             Nullable(Date),
    update_date                 DateTime
) ENGINE = MergeTree()
PARTITION BY toYYYYMM(trade_date)
ORDER BY (board_code, trade_date)
SETTINGS index_granularity = 8192;

-- 5. 板块基础维表（需要覆盖 → ReplacingMergeTree）
-- 唯一键 (board_type, board_code, data_source)；data_source 越大优先级越高
CREATE TABLE IF NOT EXISTS board_basic (
    board_type              Int32 NOT NULL,
    code                    Nullable(String),
    board_code              String NOT NULL,
    board_name              Nullable(String),
    features                Nullable(String),
    status                  Nullable(UInt8),
    data_source             UInt8 NOT NULL DEFAULT 0,
    create_date             Date,
    update_date             Nullable(DateTime),
    _ver                    UInt8 MATERIALIZED data_source
) ENGINE = ReplacingMergeTree(_ver)
PARTITION BY toYYYYMM(create_date)
ORDER BY (board_type, board_code, data_source)
SETTINGS index_granularity = 8192;

-- ========== 二、池表（涨跌停/炸板/强势/次新） ==========

-- 6. 涨停池
CREATE TABLE IF NOT EXISTS limit_up_pool (
    trade_date              Date NOT NULL,
    ts_code                 String NOT NULL,
    stock_name              Nullable(String),
    latest_price            Nullable(Decimal64(2)),
    pct_chg                 Nullable(Decimal64(4)),
    board_pos               Nullable(Int32),
    is_first                Nullable(UInt8),
    is_continuous           Nullable(UInt8),
    limit_style             Nullable(String),
    open_time               Nullable(String),
    last_time               Nullable(String),
    open_times              Nullable(Int32),
    fund                    Nullable(Decimal64(2)),
    amount                  Nullable(Decimal64(2)),
    ltsz                    Nullable(Decimal64(2)),
    tshare                  Nullable(Decimal64(2)),
    turnover_rate           Nullable(Decimal64(4)),
    board_code              Nullable(String),
    zttj_ct                 Nullable(Int32),
    zttj_days               Nullable(Int32),
    data_source             UInt8 NOT NULL DEFAULT 0,
    src_detail              Nullable(String),
    create_date             Nullable(Date),
    update_date             DateTime
) ENGINE = MergeTree()
PARTITION BY toYYYYMM(trade_date)
ORDER BY (ts_code, trade_date, data_source)
SETTINGS index_granularity = 8192;

-- 7. 跌停池
CREATE TABLE IF NOT EXISTS limit_down_pool (
    trade_date              Date NOT NULL,
    ts_code                 String NOT NULL,
    stock_name              Nullable(String),
    latest_price            Nullable(Decimal64(2)),
    pct_chg                 Nullable(Decimal64(4)),
    pe                      Nullable(Decimal64(2)),
    fund                    Nullable(Decimal64(2)),
    last_time               Nullable(String),
    fba                     Nullable(Decimal64(2)),
    days                    Nullable(Int32),
    oc                      Nullable(Int32),
    amount                  Nullable(Decimal64(2)),
    ltsz                    Nullable(Decimal64(2)),
    tshare                  Nullable(Decimal64(2)),
    turnover_rate           Nullable(Decimal64(4)),
    board_code              Nullable(String),
    data_source             UInt8 NOT NULL DEFAULT 0,
    src_detail              Nullable(String),
    create_date             Nullable(Date),
    update_date             DateTime
) ENGINE = MergeTree()
PARTITION BY toYYYYMM(trade_date)
ORDER BY (ts_code, trade_date, data_source)
SETTINGS index_granularity = 8192;

-- 8. 炸板池
CREATE TABLE IF NOT EXISTS zhaban_pool (
    trade_date              Date NOT NULL,
    ts_code                 String NOT NULL,
    stock_name              Nullable(String),
    latest_price            Nullable(Decimal64(2)),
    pct_chg                 Nullable(Decimal64(4)),
    ztp                     Nullable(Decimal64(2)),
    zf                      Nullable(Decimal64(4)),
    zs                      Nullable(Decimal64(4)),
    open_time               Nullable(String),
    open_times              Nullable(Int32),
    amount                  Nullable(Decimal64(2)),
    ltsz                    Nullable(Decimal64(2)),
    tshare                  Nullable(Decimal64(2)),
    turnover_rate           Nullable(Decimal64(4)),
    board_code              Nullable(String),
    zttj_ct                 Nullable(Int32),
    zttj_days               Nullable(Int32),
    data_source             UInt8 NOT NULL DEFAULT 0,
    src_detail              Nullable(String),
    create_date             Nullable(Date),
    update_date             DateTime
) ENGINE = MergeTree()
PARTITION BY toYYYYMM(trade_date)
ORDER BY (ts_code, trade_date, data_source)
SETTINGS index_granularity = 8192;

-- 9. 强势池
CREATE TABLE IF NOT EXISTS strong_pool (
    trade_date              Date NOT NULL,
    ts_code                 String NOT NULL,
    stock_name              Nullable(String),
    latest_price            Nullable(Decimal64(2)),
    pct_chg                 Nullable(Decimal64(4)),
    ztp                     Nullable(Decimal64(2)),
    zs                      Nullable(Decimal64(4)),
    nh                      Nullable(UInt8),
    board_pos               Nullable(Int32),
    lb                      Nullable(Decimal64(2)),
    amount                  Nullable(Decimal64(2)),
    ltsz                    Nullable(Decimal64(2)),
    tshare                  Nullable(Decimal64(2)),
    turnover_rate           Nullable(Decimal64(4)),
    board_code              Nullable(String),
    zttj_ct                 Nullable(Int32),
    zttj_days               Nullable(Int32),
    data_source             UInt8 NOT NULL DEFAULT 0,
    src_detail              Nullable(String),
    create_date             Nullable(Date),
    update_date             DateTime
) ENGINE = MergeTree()
PARTITION BY toYYYYMM(trade_date)
ORDER BY (ts_code, trade_date, data_source)
SETTINGS index_granularity = 8192;

-- 10. 次新池
CREATE TABLE IF NOT EXISTS cixin_pool (
    trade_date              Date NOT NULL,
    ts_code                 String NOT NULL,
    stock_name              Nullable(String),
    latest_price            Nullable(Decimal64(2)),
    pct_chg                 Nullable(Decimal64(4)),
    ztp                     Nullable(Decimal64(2)),
    ods                     Nullable(Int32),
    od                      Nullable(String),
    ipod                    Nullable(String),
    o                       UInt8,
    nh                      Nullable(UInt8),
    amount                  Nullable(Decimal64(2)),
    ltsz                    Nullable(Decimal64(2)),
    tshare                  Nullable(Decimal64(2)),
    turnover_rate           Nullable(Decimal64(4)),
    board_code              Nullable(String),
    zttj_ct                 Nullable(Int32),
    zttj_days               Nullable(Int32),
    data_source             UInt8 NOT NULL DEFAULT 0,
    src_detail              Nullable(String),
    create_date             Nullable(Date),
    update_date             DateTime
) ENGINE = MergeTree()
PARTITION BY toYYYYMM(trade_date)
ORDER BY (ts_code, trade_date, data_source)
SETTINGS index_granularity = 8192;

-- ========== 三、主题表（龙虎榜/资金流/板块个股关联/北向） ==========

-- 11. 龙虎榜（主键 ts_code + trade_date）
CREATE TABLE IF NOT EXISTS dragon_tiger (
    trade_date              Date NOT NULL,
    ts_code                 String NOT NULL,
    stock_name              Nullable(String),
    reason                  Nullable(String),
    explanation             Nullable(String),
    abnormal_type           Nullable(String),
    net_buy                 Nullable(Decimal64(2)),
    total_buy               Nullable(Decimal64(2)),
    total_sell              Nullable(Decimal64(2)),
    billboard_deal_amt      Nullable(Decimal64(2)),
    accum_amount            Nullable(Decimal64(2)),
    buy_ratio               Nullable(Decimal64(4)),
    sell_ratio              Nullable(Decimal64(4)),
    buy_seat                Nullable(Int32),
    sell_seat               Nullable(Int32),
    buy_seat_new            Nullable(Int32),
    sell_seat_new           Nullable(Int32),
    change_rate             Nullable(Decimal64(4)),
    close_price             Nullable(Decimal64(4)),
    turnoverrate            Nullable(Decimal64(4)),
    free_market_cap         Nullable(Decimal64(2)),
    market                  Nullable(String),
    deal_amount_ratio       Nullable(Decimal64(4)),
    deal_net_ratio          Nullable(Decimal64(4)),
    security_inner_code     Nullable(String),
    security_type_code      Nullable(String),
    trade_id                Nullable(Int64),
    trade_market            Nullable(String),
    trade_market_code       Nullable(String),
    data_source             UInt8 NOT NULL DEFAULT 0,
    src_detail              Nullable(String),
    create_date             Nullable(Date),
    update_date             DateTime
) ENGINE = MergeTree()
PARTITION BY toYYYYMM(trade_date)
ORDER BY (ts_code, trade_date)
SETTINGS index_granularity = 8192;

-- 12. 龙虎榜席位明细
CREATE TABLE IF NOT EXISTS dt_detail (
    trade_date              Date NOT NULL,
    ts_code                 String NOT NULL,
    seat_name               String NOT NULL,
    seat_type               Nullable(String),
    buy                     Nullable(Decimal64(2)),
    sell                    Nullable(Decimal64(2)),
    is_institution          Nullable(UInt8),
    is_famous               Nullable(UInt8),
    data_source             UInt8 NOT NULL DEFAULT 0,
    src_detail              Nullable(String),
    create_date             Nullable(Date),
    update_date             DateTime
) ENGINE = MergeTree()
PARTITION BY toYYYYMM(trade_date)
ORDER BY (ts_code, trade_date, seat_name)
SETTINGS index_granularity = 8192;

-- 13. 主力资金流
CREATE TABLE IF NOT EXISTS main_fund_flow (
    trade_date              Date NOT NULL,
    obj_type                String NOT NULL,
    ts_code                 String NOT NULL,
    board_code              String NOT NULL,
    index_code              String NOT NULL,
    main_net                Nullable(Decimal64(2)),
    super_big               Nullable(Decimal64(2)),
    big_net                 Nullable(Decimal64(2)),
    mid_net                 Nullable(Decimal64(2)),
    small_net               Nullable(Decimal64(2)),
    data_source             UInt8 NOT NULL DEFAULT 0,
    src_detail              Nullable(String),
    create_date             Nullable(Date),
    update_date             DateTime
) ENGINE = MergeTree()
PARTITION BY toYYYYMM(trade_date)
ORDER BY (obj_type, ts_code, board_code, index_code, trade_date)
SETTINGS index_granularity = 8192;

-- 14. 板块-个股关联（需要覆盖 → ReplacingMergeTree）
CREATE TABLE IF NOT EXISTS stock_board_rel (
    ts_code                 String NOT NULL,
    board_code              String NOT NULL,
    board_name              Nullable(String),
    stock_name              Nullable(String),
    board_type              Int32 NOT NULL,
    is_leader               Nullable(UInt8),
    is_midarm               Nullable(UInt8),
    weight                  Nullable(Decimal64(4)),
    effective_date          Date NOT NULL,
    data_source             UInt8 NOT NULL DEFAULT 0,
    src_detail              Nullable(String),
    create_date             Nullable(Date),
    update_date             Nullable(DateTime),
    _ver                    UInt8 MATERIALIZED data_source
) ENGINE = ReplacingMergeTree(_ver)
PARTITION BY toYYYYMM(effective_date)
ORDER BY (board_code, ts_code, board_type, data_source)
SETTINGS index_granularity = 8192;

-- 15. 北向资金
CREATE TABLE IF NOT EXISTS northbound_flow (
    trade_date              Date NOT NULL,
    hk_hold_net             Decimal64(2),
    sh_net                  Decimal64(2),
    sz_net                  Decimal64(2),
    data_source             UInt8 NOT NULL DEFAULT 0,
    src_detail              String,
    create_date             Date,
    update_date             DateTime
) ENGINE = MergeTree()
ORDER BY trade_date
SETTINGS index_granularity = 8192;

-- ========== 四、辅助表 ==========

-- 16. 概念主题（维表，需要覆盖）
CREATE TABLE IF NOT EXISTS concept (
    theme_code              String NOT NULL,
    theme_name              String,
    theme_type              String,
    scarcity                Decimal64(4),
    imagination             Decimal64(4),
    data_source             UInt8 DEFAULT 0,
    src_detail              String,
    create_date             Date,
    update_date             DateTime,
    _ver                    UInt8 MATERIALIZED data_source
) ENGINE = ReplacingMergeTree(_ver)
ORDER BY theme_code
SETTINGS index_granularity = 8192;

-- 17. 财务报表
CREATE TABLE IF NOT EXISTS financial (
    ts_code                 String NOT NULL,
    end_date                Date NOT NULL,
    report_type             String,
    ann_date                Date,
    revenue                 Decimal64(2),
    net_profit              Decimal64(2),
    net_profit_yoy          Decimal64(4),
    roe                     Decimal64(4),
    data_source             UInt8 DEFAULT 0,
    src_detail              String,
    create_date             Date,
    update_date             DateTime
) ENGINE = MergeTree()
PARTITION BY toYYYYMM(end_date)
ORDER BY (ts_code, end_date)
SETTINGS index_granularity = 8192;

-- 18. 新闻事件
CREATE TABLE IF NOT EXISTS news_event (
    event_id                Int64 NOT NULL,
    event_time              DateTime,
    title                   String,
    content                 String,
    source                  String,
    category                String,
    related_board           String,
    related_ts_code         String,
    sentiment_score         Decimal64(4),
    is_policy               UInt8,
    data_source             UInt8 NOT NULL DEFAULT 0,
    src_detail              String,
    create_date             Date,
    update_date             DateTime
) ENGINE = MergeTree()
PARTITION BY toYYYYMM(toDate(event_time))
ORDER BY (event_time, event_id)
SETTINGS index_granularity = 8192;

-- 19. 情绪温度（S2，每日一条）
CREATE TABLE IF NOT EXISTS sentiment_daily (
    trade_date              Date NOT NULL,
    limit_up_cnt            Int32,
    limit_down_cnt          Int32,
    max_board_pos           Int32,
    yest_limit_ret          Decimal64(4),
    thermal                 Decimal64(4),
    regime                  String
) ENGINE = MergeTree()
ORDER BY trade_date
SETTINGS index_granularity = 8192;

-- 20. 题材炒作因子（S7）
CREATE TABLE IF NOT EXISTS theme_factor_daily (
    trade_date              Date NOT NULL,
    board_code              String NOT NULL,
    scarcity                Decimal64(4),
    imagination             Decimal64(4),
    sudden                  Decimal64(4),
    certainty               Decimal64(4),
    min_resist              Decimal64(4),
    total                   Decimal64(4)
) ENGINE = MergeTree()
PARTITION BY toYYYYMM(trade_date)
ORDER BY (board_code, trade_date)
SETTINGS index_granularity = 8192;

-- 21. 趋势股候选（S6）
CREATE TABLE IF NOT EXISTS trend_candidate_daily (
    trade_date              Date NOT NULL,
    ts_code                 String NOT NULL,
    feature_hit             UInt8,
    rs_vs_index             Decimal64(4),
    confirmed               UInt8 DEFAULT 0
) ENGINE = MergeTree()
PARTITION BY toYYYYMM(trade_date)
ORDER BY (ts_code, trade_date)
SETTINGS index_granularity = 8192;

-- 22. 四维度评分（S1）
CREATE TABLE IF NOT EXISTS four_dimension_daily (
    trade_date              Date NOT NULL,
    tech                    Decimal64(4),
    sentiment               Decimal64(4),
    fund                    Decimal64(4),
    policy                  Decimal64(4),
    composite               Decimal64(4),
    worth_trade             UInt8 DEFAULT 0,
    note                    String
) ENGINE = MergeTree()
ORDER BY trade_date
SETTINGS index_granularity = 8192;

-- 23. 交易日历
CREATE TABLE IF NOT EXISTS trade_calendar (
    trade_date              Date NOT NULL,
    is_trading              UInt8,
    data_source             UInt8 NOT NULL DEFAULT 0,
    src_detail              String,
    create_date             Date,
    update_date             DateTime
) ENGINE = MergeTree()
ORDER BY trade_date
SETTINGS index_granularity = 8192;

-- ============================================================================
-- 附：与本 SQL 配套的说明
-- ============================================================================
-- 1. 本文件幂等（全部 IF NOT EXISTS），可重复执行
-- 2. 操作型表（crawl_task / crawl_log / crawl_alert / crawl_node / trade_log）不在本文件中，
--    它们保留在 openGauss，仍由 schema-full-rebuild.sql 管理
-- 3. 查询去重写法（替代原 openGauss 的 selectDataSource + 优先级裁决）：
--    SELECT * FROM stock_daily FINAL WHERE ts_code=? AND trade_date=? LIMIT 1;
--    SELECT argMax(*, data_source) FROM stock_daily WHERE ts_code=? GROUP BY ts_code, trade_date;
-- 4. 数据迁移：pg_dump --data-only --table=stock_daily | clickhouse-client --query="INSERT INTO stock_daily FORMAT CSV"
-- ============================================================================
