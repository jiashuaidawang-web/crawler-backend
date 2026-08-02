-- 涨跌停池表（拆分独立表，openGauss 兼容）
-- 主键：(ts_code, trade_date, data_source)

-- 1. 涨停池
DROP TABLE IF EXISTS limit_up_pool;
CREATE TABLE limit_up_pool (
    trade_date        DATE         NOT NULL,
    ts_code           VARCHAR(16)  NOT NULL,
    stock_name        VARCHAR(64),
    latest_price      NUMERIC(12,2),      -- 最新价(元) = p/100
    pct_chg           NUMERIC(10,4),      -- 涨跌幅% = zdp
    board_pos         INT,                -- 连板数 = lbc
    is_first          SMALLINT DEFAULT 0, -- 是否首板（lbc=1）
    is_continuous     SMALLINT DEFAULT 0, -- 是否连板（lbc>=2）
    limit_style       VARCHAR(10),        -- 一字/换手（zbc+时间判定）
    open_time         VARCHAR(8),         -- 首次封板 HH:mm:ss = fbt
    last_time         VARCHAR(8),         -- 最后封板 HH:mm:ss = lbt
    open_times        INT,                -- 开板次数 = zbc
    fund              NUMERIC(24,2),      -- 封单资金(元) = fund
    amount            NUMERIC(24,2),      -- 成交额(元) = amount
    ltsz              NUMERIC(24,2),      -- 流通市值(元) = ltsz
    tshare            NUMERIC(24,2),      -- 总市值(元) = tshare
    turnover_rate     NUMERIC(10,4),      -- 换手率% = hs
    board_code        VARCHAR(16),        -- 行业代码 = hybk
    zttj_ct           INT,                -- 涨停次数统计 = zttj.ct
    zttj_days         INT,                -- 连板天数 = zttj.days
    data_source       SMALLINT DEFAULT 1, -- 0=东财 1=同花顺
    src_detail        VARCHAR(256),       -- 来源URL/备注
    create_date       DATE,
    update_date       TIMESTAMP,
    PRIMARY KEY (ts_code, trade_date, data_source)
);

-- 2. 跌停池
DROP TABLE IF EXISTS limit_down_pool;
CREATE TABLE limit_down_pool (
    trade_date        DATE         NOT NULL,
    ts_code           VARCHAR(16)  NOT NULL,
    stock_name        VARCHAR(64),
    latest_price      NUMERIC(12,2),
    pct_chg           NUMERIC(10,4),
    pe                NUMERIC(10,2),       -- 动态市盈率
    fund              NUMERIC(24,2),       -- 封单资金(元)
    last_time         VARCHAR(8),          -- 最后封板 HH:mm:ss = lbt
    fba               NUMERIC(24,2),       -- 板上成交额(元)
    days              INT,                 -- 连续跌停天数
    oc                INT,                 -- 开板次数
    amount            NUMERIC(24,2),
    ltsz              NUMERIC(24,2),
    tshare            NUMERIC(24,2),
    turnover_rate     NUMERIC(10,4),
    board_code        VARCHAR(16),
    data_source       SMALLINT DEFAULT 1,
    src_detail        VARCHAR(256),
    create_date       DATE,
    update_date       TIMESTAMP,
    PRIMARY KEY (ts_code, trade_date, data_source)
);

-- 3. 炸板池
DROP TABLE IF EXISTS zhaban_pool;
CREATE TABLE zhaban_pool (
    trade_date        DATE         NOT NULL,
    ts_code           VARCHAR(16)  NOT NULL,
    stock_name        VARCHAR(64),
    latest_price      NUMERIC(12,2),
    pct_chg           NUMERIC(10,4),
    ztp               NUMERIC(12,2),       -- 涨停价(元) = ztp/100
    zf                NUMERIC(10,4),       -- 振幅%
    zs                NUMERIC(10,4),       -- 涨速%（炸板池语义）
    open_time         VARCHAR(8),          -- 首次封板 = fbt
    open_times        INT,                 -- 炸板次数 = zbc
    amount            NUMERIC(24,2),
    ltsz              NUMERIC(24,2),
    tshare            NUMERIC(24,2),
    turnover_rate     NUMERIC(10,4),
    board_code        VARCHAR(16),
    zttj_ct           INT,
    zttj_days         INT,
    data_source       SMALLINT DEFAULT 1,
    src_detail        VARCHAR(256),
    create_date       DATE,
    update_date       TIMESTAMP,
    PRIMARY KEY (ts_code, trade_date, data_source)
);

-- 4. 强势池
DROP TABLE IF EXISTS strong_pool;
CREATE TABLE strong_pool (
    trade_date        DATE         NOT NULL,
    ts_code           VARCHAR(16)  NOT NULL,
    stock_name        VARCHAR(64),
    latest_price      NUMERIC(12,2),
    pct_chg           NUMERIC(10,4),
    ztp               NUMERIC(12,2),       -- 涨停价(元)
    zs                NUMERIC(10,4),       -- 涨速%（强势池语义）
    nh                SMALLINT,            -- 是否新高（1=是）
    board_pos         INT,                -- 连板数 = cc
    lb                NUMERIC(10,2),       -- 量比
    amount            NUMERIC(24,2),
    ltsz              NUMERIC(24,2),
    tshare            NUMERIC(24,2),
    turnover_rate     NUMERIC(10,4),
    board_code        VARCHAR(16),
    zttj_ct           INT,
    zttj_days         INT,
    data_source       SMALLINT DEFAULT 1,
    src_detail        VARCHAR(256),
    create_date       DATE,
    update_date       TIMESTAMP,
    PRIMARY KEY (ts_code, trade_date, data_source)
);

-- 5. 次新池
DROP TABLE IF EXISTS cixin_pool;
CREATE TABLE cixin_pool (
    trade_date        DATE         NOT NULL,
    ts_code           VARCHAR(16)  NOT NULL,
    stock_name        VARCHAR(64),
    latest_price      NUMERIC(12,2),
    pct_chg           NUMERIC(10,4),
    ztp               NUMERIC(12,2),       -- 涨停价(元)，9999999→NULL
    ods               INT,                 -- 开板几日
    od                VARCHAR(8),          -- 开板日期 YYYYMMDD
    ipod              VARCHAR(8),          -- 上市日期 YYYYMMDD
    o                 SMALLINT,            -- 是否新高（1=是）
    nh                SMALLINT,            -- 新高备用
    amount            NUMERIC(24,2),
    ltsz              NUMERIC(24,2),
    tshare            NUMERIC(24,2),
    turnover_rate     NUMERIC(10,4),
    board_code        VARCHAR(16),
    zttj_ct           INT,
    zttj_days         INT,
    data_source       SMALLINT DEFAULT 1,
    src_detail        VARCHAR(256),
    create_date       DATE,
    update_date       TIMESTAMP,
    PRIMARY KEY (ts_code, trade_date, data_source)
);

-- 删旧表
DROP TABLE IF EXISTS limit_pool;
