-- ============================================================================
-- 股票复盘系统 · ClickHouse 完整建库 DDL（修正版 v2，2026-08-07）
-- 用途：ClickHouse 数据库 crawler 全部分析表一键重建（幂等）
-- 运行：clickhouse-client --host <ck> --port=8123 -d crawler < clickhouse-schema.sql
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
--
-- ============================================================================
-- 【2026-08-07 修正说明 —— 解决迁移"空校验"报错】
--   原 DDL 在 openGauss → ClickHouse 迁移时大量报 "Cannot insert NULL into
--   non-nullable column" / 语法错误。根因两类：
--
--   ① 语法错误（直接让建表脚本中断）：
--        board_daily 中 `yesterday_received_priceNullable(Decimal64(4))` 与
--        `circulation_market_valueNullable(Decimal64(2))` 列名与类型之间漏了空格，
--       被 CK 解析成非法标识符 → SYNTAX_ERROR。本版已修正为带空格的 Nullable(...)。
--
--   ② 过度 NOT NULL（迁移 INSERT 时空值触发校验失败）：
--        很多列在源库 openGauss 里是 NULLABLE，但 DDL 写成 NOT NULL；
--        且 CK 比源库多出了 create_date / update_date 等审计列（源库部分表根本没有
--        这些列，如 stock_daily / stock_weekly / index_daily / dragon_tiger /
--        dt_detail / northbound_flow / news_event / financial 及其计算层表），
--        迁移写入时只能填 NULL → 报错。
--        另有 main_fund_flow 的 ts_code / board_code / index_code 按维度区分，
--        非本维度的行这些列为 NULL（如 obj_type='stock' 时 board_code/index_code
--        为 NULL），原 NOT NULL 直接失败。
--
--   修复原则（迁移友好、零空校验失败）：
--        ★ 仅「自然键 / ORDER BY 键」保持 NOT NULL（trade_date / ts_code /
--          board_code / index_code / obj_type / seat_name / event_id /
--          theme_code / end_date / board_type / data_source）；
--        ★ 其余所有列（含审计列 create_date / update_date、维度可空列、
--          计算层业务列、status/flag 类小字段）一律 Nullable(...)；
--        ★ data_source 保留 `NOT NULL DEFAULT 0` 作为默认溯源；
--        ★ 原无默认值的 flag 列（is_policy / confirmed / worth_trade / is_trading）
--          补 DEFAULT 0，避免 INSERT 漏列报错。
--   说明：放宽 NULL 不影响查询语义；下游 replay 计算层对 NULL 做了空安全处理。
-- ============================================================================

-- 需要先建库（按需取消下面注释）
-- CREATE DATABASE IF NOT EXISTS crawler;

-- ========== 一、行情核心表 ==========

-- 1. 个股日线（主键 ts_code + trade_date，查询最高频）
CREATE TABLE IF NOT EXISTS stock_daily (
    trade_date              Date NOT NULL,
    ts_code                 String NOT NULL,
    stock_name              Nullable(String),
    -- 自然键 (ts_code, trade_date, data_source)：预留同花顺等多源共存
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
    super_big               Nullable(Decimal64(2)),
    big_net                 Nullable(Decimal64(2)),
    mid_net                 Nullable(Decimal64(2)),
    small_net               Nullable(Decimal64(2)),
    pe_static               Nullable(Decimal64(4)),
    leader_code             Nullable(String),
    industry_code           Nullable(String),
    concept_code            Nullable(String),
    market_code             Nullable(Int32),
    velocity                Nullable(Decimal64(4)),
    turn_speed              Nullable(Decimal64(4)),
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
    update_date             Nullable(DateTime)
) ENGINE = MergeTree()
PARTITION BY toYYYYMM(trade_date)
ORDER BY (ts_code, trade_date, data_source)
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
    update_date             Nullable(DateTime)
) ENGINE = MergeTree()
PARTITION BY toYYYYMM(trade_date)
ORDER BY (ts_code, trade_date, data_source)
SETTINGS index_granularity = 8192;

-- 2b. 个股分钟K线（量价数据，精确到分钟）
CREATE TABLE IF NOT EXISTS stock_kline_minute (
    trade_date      Date NOT NULL,
    ts_code         String NOT NULL,
    stock_name      Nullable(String),
    minute_time     DateTime NOT NULL,       -- 精确到分钟，如 2026-08-07 09:31:00
    open            Nullable(Decimal64(4)),   -- 开盘价
    high            Nullable(Decimal64(4)),   -- 最高价
    low             Nullable(Decimal64(4)),   -- 最低价
    close           Nullable(Decimal64(4)),   -- 收盘价
    vol             Nullable(Decimal64(4)),   -- 成交量(手)
    amount          Nullable(Decimal64(2)),   -- 成交额(元)
    amplitude       Nullable(Decimal64(4)),   -- 振幅%
    pct_chg         Nullable(Decimal64(4)),   -- 涨跌幅%
    turnover        Nullable(Decimal64(4)),   -- 换手率%
    data_source     UInt8 NOT NULL DEFAULT 1, -- 1=东财
    create_date     Date DEFAULT today()
) ENGINE = MergeTree()
PARTITION BY toYYYYMM(trade_date)
ORDER BY (ts_code, minute_time)
SETTINGS index_granularity = 8192;
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
    update_date             Nullable(DateTime)
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
    yesterday_received_price Nullable(Decimal64(4)),
    volume_ratio            Nullable(Decimal64(4)),
    turnover_ratio          Nullable(Decimal64(4)),
    total_market_value      Nullable(Decimal64(2)),
    circulation_market_value Nullable(Decimal64(2)),
    data_source             UInt8 NOT NULL DEFAULT 0,
    src_detail              Nullable(String),
    create_date             Nullable(Date),
    update_date             Nullable(DateTime)
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
    create_date             Nullable(Date),
    update_date             Nullable(DateTime),
    _ver                    UInt8 MATERIALIZED data_source
) ENGINE = ReplacingMergeTree(_ver)
PARTITION BY toYYYYMM(create_date)
ORDER BY (board_type, board_code, data_source)
SETTINGS index_granularity = 8192, allow_nullable_key = 1;

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
    update_date             Nullable(DateTime)
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
    update_date             Nullable(DateTime)
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
    update_date             Nullable(DateTime)
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
    update_date             Nullable(DateTime)
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
    o                       Nullable(UInt8),
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
    update_date             Nullable(DateTime)
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
    net_bs_amt              Nullable(Decimal64(2)),    // NET_BS_AMT          龙虎榜净买卖额(另一口径)
    sum_buy_amt             Nullable(Decimal64(2)),    // SUM_BUY_AMT         买入总额(含非龙虎榜部分)
    sum_sell_amt            Nullable(Decimal64(2)),    // SUM_SELL_AMT        卖出总额
    d1_close_adjchrate      Nullable(Decimal64(4)),    // 上榜后1日复权涨跌幅%
    d2_close_adjchrate      Nullable(Decimal64(4)),    // 上榜后2日复权涨跌幅%
    d5_close_adjchrate      Nullable(Decimal64(4)),    // 上榜后5日复权涨跌幅%
    d10_close_adjchrate     Nullable(Decimal64(4)),    // 上榜后10日复权涨跌幅%
    d20_close_adjchrate     Nullable(Decimal64(4)),    // 上榜后20日复权涨跌幅%
    d30_close_adjchrate     Nullable(Decimal64(4)),    // 上榜后30日复权涨跌幅%
    data_source             UInt8 NOT NULL DEFAULT 0,
    src_detail              Nullable(String),
    create_date             Nullable(Date),
    update_date             Nullable(DateTime)
) ENGINE = MergeTree()
PARTITION BY toYYYYMM(trade_date)
ORDER BY (ts_code, trade_date, reason)
SETTINGS index_granularity = 8192;

-- 12. 龙虎榜席位明细
CREATE TABLE IF NOT EXISTS dt_detail (
    trade_date              Date NOT NULL,
    ts_code                 String NOT NULL,
    seat_name               String NOT NULL,
    seat_type               Nullable(String),
    rank                    Nullable(Int32),            // 排名
    buy                     Nullable(Decimal64(2)),
    sell                    Nullable(Decimal64(2)),
    net_buy                 Nullable(Decimal64(2)),     // 净买入
    buy_ratio               Nullable(Decimal64(4)),     // 买入占比%
    sell_ratio              Nullable(Decimal64(4)),     // 卖出占比%
    net_buy_ratio           Nullable(Decimal64(4)),     // 净买入占比%
    trade_amt               Nullable(Decimal64(2)),     // 成交额
    trade_ratio             Nullable(Decimal64(4)),     // 成交额占比%
    accum_volume            Nullable(Decimal64(2)),     // 累计成交量(手)
    accum_amount            Nullable(Decimal64(2)),     // 累计成交额
    change_rate             Nullable(Decimal64(4)),     // 期间涨跌幅%
    turnoverrate_ratio      Nullable(Decimal64(4)),     // 期间换手率%
    trade_direction         Nullable(Int32),            // 交易方向
    statistics_days         Nullable(Int32),            // 统计天数
    onlist_times            Nullable(Int32),            // 上榜次数
    start_date              Nullable(Date),             // 统计起始日
    end_date                Nullable(Date),             // 统计截止日
    operate_dept_code       Nullable(String),           // 席位编号
    operate_dept_type       Nullable(Int32),            // 席位类型码
    change_type             Nullable(String),           // 异常类型码
    explanation             Nullable(String),           // 上榜原因
    trade_id                Nullable(Int64),            // 关联主表交易ID
    security_inner_code     Nullable(String),           // 证券内部编码
    sec_type                Nullable(Int32),            // 证券类型
    is_institution          Nullable(UInt8),
    is_famous               Nullable(UInt8),
    data_source             UInt8 NOT NULL DEFAULT 0,
    src_detail              Nullable(String),
    create_date             Nullable(Date),
    update_date             Nullable(DateTime)
) ENGINE = MergeTree()
PARTITION BY toYYYYMM(trade_date)
ORDER BY (ts_code, trade_date, seat_name, seat_type)
SETTINGS index_granularity = 8192;

-- 13. 主力资金流
-- 注意：obj_type 决定哪几列有效，非本维度的列在源库为 NULL，
-- 故 ts_code / board_code / index_code 改为 Nullable（仍是 ORDER BY 成员，CK 允许 Nullable 排序键）。
CREATE TABLE IF NOT EXISTS main_fund_flow (
    trade_date              Date NOT NULL,
    obj_type                String NOT NULL,
    ts_code                 Nullable(String),
    board_code              Nullable(String),
    index_code              Nullable(String),
    name                    Nullable(String),
    main_net                Nullable(Decimal64(2)),
    super_big               Nullable(Decimal64(2)),
    big_net                 Nullable(Decimal64(2)),
    mid_net                 Nullable(Decimal64(2)),
    small_net               Nullable(Decimal64(2)),
    data_source             UInt8 NOT NULL DEFAULT 0,
    src_detail              Nullable(String),
    create_date             Nullable(Date),
    update_date             Nullable(DateTime)
) ENGINE = MergeTree()
PARTITION BY toYYYYMM(trade_date)
ORDER BY (obj_type, ts_code, board_code, index_code, trade_date, data_source)
SETTINGS index_granularity = 8192, allow_nullable_key = 1;

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
    effective_date          Nullable(Date),
    data_source             UInt8 NOT NULL DEFAULT 0,
    trade_date              Nullable(Date),
    src_detail              Nullable(String),
    create_date             Nullable(Date),
    update_date             Nullable(DateTime),
    _ver                    UInt8 MATERIALIZED data_source
) ENGINE = ReplacingMergeTree(_ver)
PARTITION BY toYYYYMM(effective_date)
ORDER BY (board_code, ts_code, board_type, data_source)
SETTINGS index_granularity = 8192, allow_nullable_key = 1;

-- 15. 北向资金
CREATE TABLE IF NOT EXISTS northbound_flow (
    trade_date              Date NOT NULL,
    hk_hold_net             Nullable(Decimal64(2)),
    sh_net                  Nullable(Decimal64(2)),
    sz_net                  Nullable(Decimal64(2)),
    data_source             UInt8 NOT NULL DEFAULT 0,
    src_detail              Nullable(String),
    create_date             Nullable(Date),
    update_date             Nullable(DateTime)
) ENGINE = MergeTree()
ORDER BY trade_date
SETTINGS index_granularity = 8192;

-- ========== 四、辅助表 ==========

-- 16. 概念主题（维表，需要覆盖）
CREATE TABLE IF NOT EXISTS concept (
    theme_code              String NOT NULL,
    theme_name              Nullable(String),
    theme_type              Nullable(String),
    scarcity                Nullable(Decimal64(4)),
    imagination             Nullable(Decimal64(4)),
    data_source             UInt8 NOT NULL DEFAULT 0,
    src_detail              Nullable(String),
    create_date             Nullable(Date),
    update_date             Nullable(DateTime),
    _ver                    UInt8 MATERIALIZED data_source
) ENGINE = ReplacingMergeTree(_ver)
ORDER BY theme_code
SETTINGS index_granularity = 8192;

-- 17. 财务报表
CREATE TABLE IF NOT EXISTS financial (
    ts_code                 String NOT NULL,
    end_date                Date NOT NULL,
    report_type             Nullable(String),
    ann_date                Nullable(Date),
    revenue                 Nullable(Decimal64(2)),
    net_profit              Nullable(Decimal64(2)),
    net_profit_yoy          Nullable(Decimal64(4)),
    roe                     Nullable(Decimal64(4)),
    data_source             UInt8 NOT NULL DEFAULT 0,
    src_detail              Nullable(String),
    create_date             Nullable(Date),
    update_date             Nullable(DateTime)
) ENGINE = MergeTree()
PARTITION BY toYYYYMM(end_date)
ORDER BY (ts_code, end_date)
SETTINGS index_granularity = 8192;

-- 18. 新闻事件
CREATE TABLE IF NOT EXISTS news_event (
    event_id                Int64 NOT NULL,
    event_time              Nullable(DateTime),
    title                   Nullable(String),
    content                 Nullable(String),
    source                  Nullable(String),
    category                Nullable(String),
    related_board           Nullable(String),
    related_ts_code         Nullable(String),
    sentiment_score         Nullable(Decimal64(4)),
    is_policy               UInt8 DEFAULT 0,
    data_source             UInt8 NOT NULL DEFAULT 0,
    src_detail              Nullable(String),
    create_date             Nullable(Date),
    update_date             Nullable(DateTime)
) ENGINE = MergeTree()
PARTITION BY toYYYYMM(toDate(event_time))
ORDER BY (event_time, event_id)
SETTINGS index_granularity = 8192, allow_nullable_key = 1;

-- 19. 情绪温度（S2，每日一条）
CREATE TABLE IF NOT EXISTS sentiment_daily (
    trade_date              Date NOT NULL,
    limit_up_cnt            Nullable(Int32),
    limit_down_cnt          Nullable(Int32),
    max_board_pos           Nullable(Int32),
    yest_limit_ret          Nullable(Decimal64(4)),
    thermal                 Nullable(Decimal64(4)),
    regime                  Nullable(String),
    _ver                    DateTime MATERIALIZED now()  -- 版本列：同 trade_date 保留最新
) ENGINE = ReplacingMergeTree(_ver)
ORDER BY trade_date
SETTINGS index_granularity = 8192;

-- 20. 题材炒作因子（S7）
CREATE TABLE IF NOT EXISTS theme_factor_daily (
    trade_date              Date NOT NULL,
    board_code              String NOT NULL,
    scarcity                Nullable(Decimal64(4)),
    imagination             Nullable(Decimal64(4)),
    sudden                  Nullable(Decimal64(4)),
    certainty               Nullable(Decimal64(4)),
    min_resist              Nullable(Decimal64(4)),
    total                   Nullable(Decimal64(4))
) ENGINE = MergeTree()
PARTITION BY toYYYYMM(trade_date)
ORDER BY (board_code, trade_date)
SETTINGS index_granularity = 8192;

-- 21. 趋势股候选（S6）
CREATE TABLE IF NOT EXISTS trend_candidate_daily (
    trade_date              Date NOT NULL,
    ts_code                 String NOT NULL,
    feature_hit             Nullable(UInt8),
    rs_vs_index             Nullable(Decimal64(4)),
    confirmed               UInt8 DEFAULT 0
) ENGINE = MergeTree()
PARTITION BY toYYYYMM(trade_date)
ORDER BY (ts_code, trade_date)
SETTINGS index_granularity = 8192;

-- 22. 四维度评分（S1）
CREATE TABLE IF NOT EXISTS four_dimension_daily (
    trade_date              Date NOT NULL,
    tech                    Nullable(Decimal64(4)),
    sentiment               Nullable(Decimal64(4)),
    fund                    Nullable(Decimal64(4)),
    policy                  Nullable(Decimal64(4)),
    composite               Nullable(Decimal64(4)),
    worth_trade             UInt8 DEFAULT 0,
    note                    Nullable(String)
) ENGINE = MergeTree()
ORDER BY trade_date
SETTINGS index_granularity = 8192;

-- 23. 交易日历（ReplacingMergeTree：重复 seed 同 trade_date 自动保留新版，幂等）
CREATE TABLE IF NOT EXISTS trade_calendar (
    trade_date              Date NOT NULL,
    is_trading              UInt8 NOT NULL DEFAULT 0,   -- 1=交易日 0=休市（总是有值，不再 Nullable）
    data_source             UInt8 NOT NULL DEFAULT 0,
    src_detail              Nullable(String),
    create_date             Nullable(Date),
    update_date             Nullable(DateTime),
    _ver                    DateTime MATERIALIZED now()  -- 版本列：同 trade_date 保留最新
) ENGINE = ReplacingMergeTree(_ver)
ORDER BY trade_date
SETTINGS index_granularity = 8192;

-- 24. 主线识别（S4 计算层产出，trade_date+board_code 为自然键）
CREATE TABLE IF NOT EXISTS mainline_daily (
    trade_date              Date NOT NULL,
    board_code              String NOT NULL,
    main_level              Nullable(String),
    strength                Nullable(Decimal(8, 4)),
    rank                    Nullable(Int32),
    _ver                    DateTime MATERIALIZED now()  -- 版本列：同 (trade_date, board_code) 保留最新
) ENGINE = ReplacingMergeTree(_ver)
PARTITION BY toYYYYMM(trade_date)
ORDER BY (trade_date, board_code)
SETTINGS index_granularity = 8192;

-- 25. 龙头池（S4 计算层产出，trade_date+ts_code+board_code 为自然键）
CREATE TABLE IF NOT EXISTS leader_pool_daily (
    trade_date              Date NOT NULL,
    ts_code                 String NOT NULL,
    board_code              Nullable(String),
    board_pos               Nullable(Int16),
    role                    Nullable(String),
    score                   Nullable(Decimal(8, 4)),
    _ver                    DateTime MATERIALIZED now()  -- 版本列：同 (trade_date, ts_code, board_code) 保留最新
) ENGINE = ReplacingMergeTree(_ver)
PARTITION BY toYYYYMM(trade_date)
ORDER BY (trade_date, ts_code, board_code)
SETTINGS index_granularity = 8192, allow_nullable_key = 1;

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
-- 5. 【修正 v2】若在线库已有旧表（含过度 NOT NULL），请配套执行 clickhouse-schema-fix.sql
--    用 ALTER MODIFY COLUMN 把非键列改为 Nullable（仅放宽约束，不丢数据），
--    专项修复 main_fund_flow 三维度列与全部 update_date/create_date 的"空校验"报错。
-- ============================================================================

-- 股票任务配置表(分时/日K等任务的股票列表)
CREATE TABLE IF NOT EXISTS stock_task_config (
    type String COMMENT '任务类型(如 minute=分时)',
    code String COMMENT '股票代码(600000.SH)',
    stock_name String COMMENT '股票名称',
    status UInt8 DEFAULT 1 COMMENT '1=启用 0=禁用',
    create_date Date COMMENT '创建日期',
    update_date DateTime COMMENT '更新时间'
) ENGINE = MergeTree()
ORDER BY (type, code)
COMMENT '股票任务配置表';
