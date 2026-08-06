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
    stock_name              String,
    open                    Decimal64(4),
    high                    Decimal64(4),
    low                     Decimal64(4),
    close                   Decimal64(4),
    pre_close               Decimal64(4),
    pct_chg                 Decimal64(4),
    vol                     Decimal64(4),
    amount                  Decimal64(4),
    turnover                Decimal64(4),
    total_mv                Decimal64(2),
    circ_mv                 Decimal64(2),
    pe                      Decimal64(4),
    is_limit_up             UInt8,
    is_limit_down           UInt8,
    chg_amount              Decimal64(4),
    amplitude               Decimal64(4),
    volume_ratio            Decimal64(4),
    avg_price               Decimal64(4),
    main_net                Decimal64(2),
    pe_static               Decimal64(4),
    leader_code             String,
    industry_code           String,
    concept_code            String,
    market_code             Int32,
    velocity                Decimal64(4),
    is_new_high             UInt8,
    chg_60d                 Decimal64(4),
    seal_fund               Decimal64(2),
    board_days              Int32,
    board_stat              String,
    first_seal_time         String,
    last_seal_time          String,
    limit_type              Int32,
    reserved_f24            Decimal64(4),
    reserved_f25            Decimal64(4),
    reserved_f107           Decimal64(4),
    reserved_f136           Decimal64(2),
    reserved_f173           Decimal64(4),
    data_source             UInt8 NOT NULL DEFAULT 0,
    src_detail              String,
    create_date             Date,
    update_date             DateTime
) ENGINE = MergeTree()
PARTITION BY toYYYYMM(trade_date)
ORDER BY (ts_code, trade_date)
SETTINGS index_granularity = 8192;

-- 2. 个股周线
CREATE TABLE IF NOT EXISTS stock_weekly (
    trade_date              Date NOT NULL,
    ts_code                 String NOT NULL,
    stock_name              String,
    open                    Decimal64(4),
    high                    Decimal64(4),
    low                     Decimal64(4),
    close                   Decimal64(4),
    vol                     Decimal64(4),
    amount                  Decimal64(4),
    chg_amount              Decimal64(4),
    amplitude               Decimal64(4),
    volume_ratio            Decimal64(4),
    avg_price               Decimal64(4),
    main_net                Decimal64(2),
    pe_static               Decimal64(4),
    leader_code             String,
    industry_code           String,
    concept_code            String,
    market_code             Int32,
    data_source             UInt8 NOT NULL DEFAULT 0,
    src_detail              String,
    create_date             Date,
    update_date             DateTime
) ENGINE = MergeTree()
PARTITION BY toYYYYMM(trade_date)
ORDER BY (ts_code, trade_date)
SETTINGS index_granularity = 8192;

-- 3. 指数日线
CREATE TABLE IF NOT EXISTS index_daily (
    trade_date              Date NOT NULL,
    index_code              String NOT NULL,
    index_name              String,
    open                    Decimal64(4),
    high                    Decimal64(4),
    low                     Decimal64(4),
    close                   Decimal64(4),
    pre_close               Decimal64(4),
    pct_chg                 Decimal64(4),
    vol                     Decimal64(4),
    amount                  Decimal64(4),
    turnover                Decimal64(4),
    data_source             UInt8 NOT NULL DEFAULT 0,
    src_detail              String,
    create_date             Date,
    update_date             DateTime
) ENGINE = MergeTree()
PARTITION BY toYYYYMM(trade_date)
ORDER BY (index_code, trade_date)
SETTINGS index_granularity = 8192;

-- 4. 板块日线
CREATE TABLE IF NOT EXISTS board_daily (
    trade_date                  Date NOT NULL,
    board_code                  String NOT NULL,
    board_name                  String,
    board_type                  Int32,
    pct_chg                     Decimal64(4),
    amount                      Decimal64(4),
    up_count                    Int32,
    down_count                  Int32,
    limit_up_count              Int32,
    leading_code                String,
    leading_name                String,
    main_net                    Decimal64(4),
    board_code2                 String,
    price                       Decimal64(4),
    rise_fall                   Decimal64(4),
    volume                      Decimal64(4),
    amplitude                   Decimal64(4),
    high_price                  Decimal64(4),
    low_price                   Decimal64(4),
    today_open_price            Decimal64(4),
    yesterday_received_price    Decimal64(4),
    volume_ratio                Decimal64(4),
    turnover_ratio              Decimal64(4),
    total_market_value          Decimal64(4),
    circulation_market_value    Decimal64(4),
    data_source                 UInt8 NOT NULL DEFAULT 0,
    src_detail                  String,
    create_date                 Date,
    update_date                 DateTime
) ENGINE = MergeTree()
PARTITION BY toYYYYMM(trade_date)
ORDER BY (board_code, trade_date)
SETTINGS index_granularity = 8192;

-- 5. 板块基础维表（需要覆盖 → ReplacingMergeTree）
-- 唯一键 (board_type, board_code, data_source)；data_source 越大优先级越高
CREATE TABLE IF NOT EXISTS board_basic (
    board_type              Int32 NOT NULL,
    code                    String,
    board_code              String NOT NULL,
    board_name              String NOT NULL,
    features                String,
    status                  UInt8 DEFAULT 1,
    data_source             UInt8 NOT NULL DEFAULT 0,
    create_date             Date,
    update_date             DateTime,
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
    stock_name              String,
    latest_price            Decimal64(2),
    pct_chg                 Decimal64(4),
    board_pos               Int32,
    is_first                UInt8 DEFAULT 0,
    is_continuous           UInt8 DEFAULT 0,
    limit_style             String,
    open_time               String,
    last_time               String,
    open_times              Int32,
    fund                    Decimal64(2),
    amount                  Decimal64(2),
    ltsz                    Decimal64(2),
    tshare                  Decimal64(2),
    turnover_rate           Decimal64(4),
    board_code              String,
    zttj_ct                 Int32,
    zttj_days               Int32,
    data_source             UInt8 NOT NULL DEFAULT 0,
    src_detail              String,
    create_date             Date,
    update_date             DateTime
) ENGINE = MergeTree()
PARTITION BY toYYYYMM(trade_date)
ORDER BY (ts_code, trade_date, data_source)
SETTINGS index_granularity = 8192;

-- 7. 跌停池
CREATE TABLE IF NOT EXISTS limit_down_pool (
    trade_date              Date NOT NULL,
    ts_code                 String NOT NULL,
    stock_name              String,
    latest_price            Decimal64(2),
    pct_chg                 Decimal64(4),
    pe                      Decimal64(2),
    fund                    Decimal64(2),
    last_time               String,
    fba                     Decimal64(2),
    days                    Int32,
    oc                      Int32,
    amount                  Decimal64(2),
    ltsz                    Decimal64(2),
    tshare                  Decimal64(2),
    turnover_rate           Decimal64(4),
    board_code              String,
    data_source             UInt8 NOT NULL DEFAULT 0,
    src_detail              String,
    create_date             Date,
    update_date             DateTime
) ENGINE = MergeTree()
PARTITION BY toYYYYMM(trade_date)
ORDER BY (ts_code, trade_date, data_source)
SETTINGS index_granularity = 8192;

-- 8. 炸板池
CREATE TABLE IF NOT EXISTS zhaban_pool (
    trade_date              Date NOT NULL,
    ts_code                 String NOT NULL,
    stock_name              String,
    latest_price            Decimal64(2),
    pct_chg                 Decimal64(4),
    ztp                     Decimal64(2),
    zf                      Decimal64(4),
    zs                      Decimal64(4),
    open_time               String,
    open_times              Int32,
    amount                  Decimal64(2),
    ltsz                    Decimal64(2),
    tshare                  Decimal64(2),
    turnover_rate           Decimal64(4),
    board_code              String,
    zttj_ct                 Int32,
    zttj_days               Int32,
    data_source             UInt8 NOT NULL DEFAULT 0,
    src_detail              String,
    create_date             Date,
    update_date             DateTime
) ENGINE = MergeTree()
PARTITION BY toYYYYMM(trade_date)
ORDER BY (ts_code, trade_date, data_source)
SETTINGS index_granularity = 8192;

-- 9. 强势池
CREATE TABLE IF NOT EXISTS strong_pool (
    trade_date              Date NOT NULL,
    ts_code                 String NOT NULL,
    stock_name              String,
    latest_price            Decimal64(2),
    pct_chg                 Decimal64(4),
    ztp                     Decimal64(2),
    zs                      Decimal64(4),
    nh                      UInt8,
    board_pos               Int32,
    lb                      Decimal64(2),
    amount                  Decimal64(2),
    ltsz                    Decimal64(2),
    tshare                  Decimal64(2),
    turnover_rate           Decimal64(4),
    board_code              String,
    zttj_ct                 Int32,
    zttj_days               Int32,
    data_source             UInt8 NOT NULL DEFAULT 0,
    src_detail              String,
    create_date             Date,
    update_date             DateTime
) ENGINE = MergeTree()
PARTITION BY toYYYYMM(trade_date)
ORDER BY (ts_code, trade_date, data_source)
SETTINGS index_granularity = 8192;

-- 10. 次新池
CREATE TABLE IF NOT EXISTS cixin_pool (
    trade_date              Date NOT NULL,
    ts_code                 String NOT NULL,
    stock_name              String,
    latest_price            Decimal64(2),
    pct_chg                 Decimal64(4),
    ztp                     Decimal64(2),
    ods                     Int32,
    od                      String,
    ipod                String,
    o                       UInt8,
    nh                      UInt8,
    amount                  Decimal64(2),
    ltsz                    Decimal64(2),
    tshare                  Decimal64(2),
    turnover_rate           Decimal64(4),
    board_code              String,
    zttj_ct                 Int32,
    zttj_days               Int32,
    data_source             UInt8 NOT NULL DEFAULT 0,
    src_detail              String,
    create_date             Date,
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
    stock_name              String,
    reason                  String,
    explanation             String,
    abnormal_type           String,
    net_buy                 Decimal64(2),
    total_buy               Decimal64(2),
    total_sell              Decimal64(2),
    billboard_deal_amt      Decimal64(2),
    accum_amount            Decimal64(2),
    buy_ratio               Decimal64(4),
    sell_ratio              Decimal64(4),
    buy_seat                Int32,
    sell_seat               Int32,
    buy_seat_new            Int32,
    sell_seat_new           Int32,
    change_rate             Decimal64(4),
    close_price             Decimal64(4),
    turnoverrate            Decimal64(4),
    free_market_cap         Decimal64(2),
    market                  String,
    deal_amount_ratio       Decimal64(4),
    deal_net_ratio          Decimal64(4),
    security_inner_code     String,
    security_type_code      String,
    trade_id                Int64,
    trade_market            String,
    trade_market_code       String,
    data_source             UInt8 NOT NULL DEFAULT 0,
    src_detail              String,
    create_date             Date,
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
    seat_type               String,
    buy                     Decimal64(2),
    sell                    Decimal64(2),
    is_institution          UInt8,
    is_famous               UInt8,
    data_source             UInt8 NOT NULL DEFAULT 0,
    src_detail              String,
    create_date             Date,
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
    main_net                Decimal64(2),
    super_big               Decimal64(2),
    big_net                 Decimal64(2),
    mid_net                 Decimal64(2),
    small_net               Decimal64(2),
    data_source             UInt8 NOT NULL DEFAULT 0,
    src_detail              String,
    create_date             Date,
    update_date             DateTime
) ENGINE = MergeTree()
PARTITION BY toYYYYMM(trade_date)
ORDER BY (obj_type, ts_code, board_code, index_code, trade_date)
SETTINGS index_granularity = 8192;

-- 14. 板块-个股关联（需要覆盖 → ReplacingMergeTree）
CREATE TABLE IF NOT EXISTS stock_board_rel (
    ts_code                 String NOT NULL,
    board_code              String NOT NULL,
    board_name              String,
    stock_name              String,
    board_type              Int32 NOT NULL,
    is_leader               UInt8,
    is_midarm               UInt8,
    weight                  Decimal64(4),
    effective_date          Date NOT NULL,
    data_source             UInt8 NOT NULL DEFAULT 0,
    src_detail              String,
    create_date             Date,
    update_date             DateTime,
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
