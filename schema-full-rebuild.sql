-- ============================================================================
-- 股票复盘系统 · 完整建库 SQL（openGauss/PostgreSQL 兼容，幂等）
-- 生成时间：2026-08-03
-- 用途：数据库删空后一键重建全部表结构（含基础表 + 池表 + 关联表 + 基础设施表）
-- 运行：psql -h <host> -p <port> -U <user> -d <db> -f schema-full-rebuild.sql
--
-- 约定：
--   金额单位=元；幅度/涨跌=百分比数值；成交量=手
--   data_source: 0=东财 1=同花顺；board_type: 1地域 2行业 3概念
--   全部幂等（CREATE TABLE IF NOT EXISTS），可重复执行
-- ============================================================================

-- ============================================================================
-- 一、行情核心表
-- ============================================================================

-- 1. 个股日线（主键 ts_code + trade_date）
CREATE TABLE IF NOT EXISTS stock_daily (
    trade_date              DATE NOT NULL,
    ts_code                 VARCHAR(16) NOT NULL,
    stock_name              VARCHAR(64),
    open                    NUMERIC(12,4),
    high                    NUMERIC(12,4),
    low                     NUMERIC(12,4),
    close                   NUMERIC(12,4),
    pre_close               NUMERIC(12,4),
    pct_chg                 NUMERIC(10,4),
    vol                     NUMERIC(20,4),
    amount                  NUMERIC(20,4),
    turnover                NUMERIC(10,4),
    total_mv                NUMERIC(24,2),
    circ_mv                 NUMERIC(24,2),
    pe                      NUMERIC(12,4),
    is_limit_up             SMALLINT,
    is_limit_down           SMALLINT,
    chg_amount              NUMERIC(12,4),       -- f4  涨跌额
    amplitude               NUMERIC(10,4),       -- f7  振幅%
    volume_ratio            NUMERIC(10,4),       -- f10 量比
    avg_price               NUMERIC(12,4),       -- f11 均价
    main_net                NUMERIC(24,2),       -- f62 主力净流入
    pe_static               NUMERIC(12,4),       -- f115 静态市盈率
    leader_code             VARCHAR(16),         -- f128 领涨股代码
    industry_code           VARCHAR(16),         -- f140 所属行业代码
    concept_code            VARCHAR(16),         -- f141 所属概念代码
    market_code             SMALLINT,            -- f152 市场码(0深/1沪/2京)
    reserved_f24            NUMERIC(10,4),       -- f24  年初至今涨跌幅
    reserved_f25            NUMERIC(10,4),       -- f25  涨停价(分→元)
    reserved_f107           NUMERIC(10,4),       -- f107 待确认
    reserved_f136           NUMERIC(24,2),       -- f136 炸板次数
    reserved_f173           NUMERIC(10,4),       -- f173 涨速%
    data_source             SMALLINT NOT NULL DEFAULT 0,
    src_detail              VARCHAR(256),
    create_date             DATE,
    update_date             TIMESTAMP,
    PRIMARY KEY (ts_code, trade_date)
);
COMMENT ON TABLE stock_daily IS '个股日线（STOCK_DAILY 任务产出，push2 clist 全市场快照）';
COMMENT ON COLUMN stock_daily.market_code IS '0深 1沪 2京';

-- 2. 个股周线（主键 ts_code + trade_dat-- ============================================================================
-- -- 股票复盘系统 · 完整建库 SQL（openGauss/PostgreSQL 兼容，幂等）
-- -- 生成时间：2026-08-03
-- -- 用途：数据库删空后一键重建全部表结构（含基础表 + 池表 + 关联表 + 基础设施表）
-- -- 运行：psql -h <host> -p <port> -U <user> -d <db> -f schema-full-rebuild.sql
-- --
-- -- 约定：
-- --   金额单位=元；幅度/涨跌=百分比数值；成交量=手
-- --   data_source: 0=东财 1=同花顺；board_type: 1地域 2行业 3概念
-- --   全部幂等（CREATE TABLE IF NOT EXISTS），可重复执行
-- -- ============================================================================
--
-- -- ============================================================================
-- -- 一、行情核心表
-- -- ============================================================================
--
-- -- 1. 个股日线（主键 ts_code + trade_date）
-- CREATE TABLE IF NOT EXISTS stock_daily (
--     trade_date              DATE NOT NULL,
--     ts_code                 VARCHAR(16) NOT NULL,
--     stock_name              VARCHAR(64),
--     open                    NUMERIC(12,4),
--     high                    NUMERIC(12,4),
--     low                     NUMERIC(12,4),
--     close                   NUMERIC(12,4),
--     pre_close               NUMERIC(12,4),
--     pct_chg                 NUMERIC(10,4),
--     vol                     NUMERIC(20,4),
--     amount                  NUMERIC(20,4),
--     turnover                NUMERIC(10,4),
--     total_mv                NUMERIC(24,2),
--     circ_mv                 NUMERIC(24,2),
--     pe                      NUMERIC(12,4),
--     is_limit_up             SMALLINT,
--     is_limit_down           SMALLINT,
--     chg_amount              NUMERIC(12,4),       -- f4  涨跌额
--     amplitude               NUMERIC(10,4),       -- f7  振幅%
--     volume_ratio            NUMERIC(10,4),       -- f10 量比
--     avg_price               NUMERIC(12,4),       -- f11 均价
--     main_net                NUMERIC(24,2),       -- f62 主力净流入
--     pe_static               NUMERIC(12,4),       -- f115 静态市盈率
--     leader_code             VARCHAR(16),         -- f128 领涨股代码
--     industry_code           VARCHAR(16),         -- f140 所属行业代码
--     concept_code            VARCHAR(16),         -- f141 所属概念代码
--     market_code             SMALLINT,            -- f152 市场码(0深/1沪/2京)
--     reserved_f24            NUMERIC(10,4),       -- f24  年初至今涨跌幅
--     reserved_f25            NUMERIC(10,4),       -- f25  涨停价(分→元)
--     reserved_f107           NUMERIC(10,4),       -- f107 待确认
--     reserved_f136           NUMERIC(24,2),       -- f136 炸板次数
--     reserved_f173           NUMERIC(10,4),       -- f173 涨速%
--     data_source             SMALLINT NOT NULL DEFAULT 0,
--     src_detail              VARCHAR(256),
--     create_date             DATE,
--     update_date             TIMESTAMP,
--     PRIMARY KEY (ts_code, trade_date)
-- );
-- COMMENT ON TABLE stock_daily IS '个股日线（STOCK_DAILY 任务产出，push2 clist 全市场快照）';
-- COMMENT ON COLUMN stock_daily.market_code IS '0深 1沪 2京';
--
-- -- 2. 个股周线（主键 ts_code + trade_date）
-- CREATE TABLE IF NOT EXISTS stock_weekly (
--     trade_date              DATE NOT NULL,
--     ts_code                 VARCHAR(16) NOT NULL,
--     stock_name              VARCHAR(64),
--     open                    NUMERIC(12,4),
--     high                    NUMERIC(12,4),
--     low                     NUMERIC(12,4),
--     close                   NUMERIC(12,4),
--     vol                     NUMERIC(20,4),
--     amount                  NUMERIC(20,4),
--     chg_amount              NUMERIC(12,4),
--     amplitude               NUMERIC(10,4),
--     volume_ratio            NUMERIC(10,4),
--     avg_price               NUMERIC(12,4),
--     main_net                NUMERIC(24,2),
--     pe_static               NUMERIC(12,4),
--     leader_code             VARCHAR(16),
--     industry_code           VARCHAR(16),
--     concept_code            VARCHAR(16),
--     market_code             SMALLINT,
--     data_source             SMALLINT NOT NULL DEFAULT 0,
--     src_detail              VARCHAR(256),
--     create_date             DATE,
--     update_date             TIMESTAMP,
--     PRIMARY KEY (ts_code, trade_date)
-- );
-- COMMENT ON TABLE stock_weekly IS '个股周线（STOCK_WEEKLY 任务产出，push2his kline）';
--
-- -- 3. 指数日线（主键 index_code + trade_date）
-- CREATE TABLE IF NOT EXISTS index_daily (
--     trade_date              DATE NOT NULL,
--     index_code              VARCHAR(16) NOT NULL,   -- 如 000001.SH
--     index_name              VARCHAR(64),
--     open                    NUMERIC(12,4),
--     high                    NUMERIC(12,4),
--     low                     NUMERIC(12,4),
--     close                   NUMERIC(12,4),
--     pre_close               NUMERIC(12,4),
--     pct_chg                 NUMERIC(10,4),
--     vol                     NUMERIC(20,4),
--     amount                  NUMERIC(20,4),
--     turnover                NUMERIC(10,4),
--     data_source             SMALLINT NOT NULL DEFAULT 0,
--     src_detail              VARCHAR(256),
--     create_date             DATE,
--     update_date             TIMESTAMP,
--     PRIMARY KEY (index_code, trade_date)
-- );
-- COMMENT ON TABLE index_daily IS '指数日线（INDEX_DAILY 任务产出）';
--
-- -- 4. 板块日线（主键 board_code + trade_date）
-- --    board_type: 1地域 2行业 3概念（来自 taskType 映射，响应无区分字段）
-- CREATE TABLE IF NOT EXISTS board_daily (
--     trade_date              DATE NOT NULL,
--     board_code              VARCHAR(32) NOT NULL,
--     board_name              VARCHAR(128),
--     board_type              SMALLINT,            -- 1地域 2行业 3概念
--     pct_chg                 NUMERIC(10,4),
--     amount                  NUMERIC(20,4),
--     up_count                INTEGER,
--     down_count              INTEGER,
--     limit_up_count          INTEGER,             -- TODO M6：clist 无直接字段
--     leading_code            VARCHAR(32),         -- f140 领涨股代码
--     leading_name            VARCHAR(64),         -- f128 领涨股名称
--     main_net                NUMERIC(20,4),       -- f62  主力净流入
--     board_code2             VARCHAR(32),         -- TODO M6：接口未返回
--     data_source             SMALLINT NOT NULL DEFAULT 0,
--     src_detail              TEXT,
--     price                   NUMERIC(10,4),       -- f2  板块最新价
--     rise_fall               NUMERIC(10,4),       -- f4  涨跌额
--     volume                  NUMERIC(20,4),       -- f5  成交量(手)
--     amplitude               NUMERIC(10,4),       -- f7  振幅%
--     high_price              NUMERIC(10,4),       -- f15 最高价
--     low_price               NUMERIC(10,4),       -- f16 最低价
--     today_open_price        NUMERIC(10,4),       -- f17 今开
--     yesterday_received_price NUMERIC(10,4),      -- f18 昨收
--     volume_ratio            NUMERIC(10,4),       -- f10 量比
--     turnover_ratio          NUMERIC(10,4),       -- f8  换手率%
--     total_market_value      NUMERIC(20,4),       -- f20 总市值
--     circulation_market_value NUMERIC(20,4),      -- f21 流通市值
--     create_date             DATE,
--     update_date             TIMESTAMP,
--     PRIMARY KEY (board_code, trade_date)
-- );
-- COMMENT ON TABLE board_daily IS '板块日线（REGION/INDUSTRY/CONCEPT_DAILY 任务产出）';
-- COMMENT ON COLUMN board_daily.board_type IS '1地域 2行业 3概念（来自 taskType 映射）';
--
-- -- 5. 板块基础数据（维表，主键 id；唯一校验 board_type+board_code+data_source）
-- CREATE TABLE IF NOT EXISTS board_basic (
--     id                      BIGSERIAL PRIMARY KEY,
--     board_type              SMALLINT NOT NULL,   -- 1地域 2行业 3概念
--     code                    VARCHAR(16),         -- 同花顺板块指数代码
--     board_code              VARCHAR(16) NOT NULL,
--     board_name              VARCHAR(64) NOT NULL,
--     features                VARCHAR(340),
--     status                  SMALLINT DEFAULT 1,  -- 1正常 0删除
--     data_source             SMALLINT NOT NULL DEFAULT 0,
--     create_date             DATE NOT NULL DEFAULT CURRENT_DATE,
--     update_date             TIMESTAMP
-- );
-- COMMENT ON TABLE board_basic IS '板块基础数据表（board_daily 同步副作用维护）';
-- CREATE INDEX IF NOT EXISTS idx_bb_board_code ON board_basic(board_code);
-- CREATE INDEX IF NOT EXISTS idx_bb_board_type ON board_basic(board_type);
--
-- -- ============================================================================
-- -- 二、池表（涨跌停/炸板/强势/次新）
-- --    主键：(ts_code, trade_date, data_source)
-- -- ============================================================================
--
-- -- 6. 涨停池
-- CREATE TABLE IF NOT EXISTS limit_up_pool (
--     trade_date              DATE NOT NULL,
--     ts_code                 VARCHAR(16) NOT NULL,
--     stock_name              VARCHAR(64),
--     latest_price            NUMERIC(12,2),       -- 最新价(元) = p/100
--     pct_chg                 NUMERIC(10,4),       -- 涨跌幅%
--     board_pos               INT,                 -- 连板数 = lbc
--     is_first                SMALLINT DEFAULT 0,  -- 是否首板（lbc=1）
--     is_continuous           SMALLINT DEFAULT 0,  -- 是否连板（lbc>=2）
--     limit_style             VARCHAR(10),         -- 一字/换手
--     open_time               VARCHAR(8),          -- 首次封板 HH:mm:ss = fbt
--     last_time               VARCHAR(8),          -- 最后封板 HH:mm:ss = lbt
--     open_times              INT,                 -- 开板次数 = zbc
--     fund                    NUMERIC(24,2),       -- 封单资金(元)
--     amount                  NUMERIC(24,2),       -- 成交额(元)
--     ltsz                    NUMERIC(24,2),       -- 流通市值(元)
--     tshare                  NUMERIC(24,2),       -- 总市值(元)
--     turnover_rate           NUMERIC(10,4),       -- 换手率%
--     board_code              VARCHAR(16),         -- 行业代码 = hybk
--     zttj_ct                 INT,                 -- 涨停次数统计
--     zttj_days               INT,                 -- 连板天数
--     data_source             SMALLINT NOT NULL DEFAULT 0,
--     src_detail              VARCHAR(256),
--     create_date             DATE,
--     update_date             TIMESTAMP,
--     PRIMARY KEY (ts_code, trade_date, data_source)
-- );
-- COMMENT ON TABLE limit_up_pool IS '涨停池（LIMIT_UP 任务产出）';
--
-- -- 7. 跌停池
-- CREATE TABLE IF NOT EXISTS limit_down_pool (
--     trade_date              DATE NOT NULL,
--     ts_code                 VARCHAR(16) NOT NULL,
--     stock_name              VARCHAR(64),
--     latest_price            NUMERIC(12,2),
--     pct_chg                 NUMERIC(10,4),
--     pe                      NUMERIC(10,2),       -- 动态市盈率
--     fund                    NUMERIC(24,2),
--     last_time               VARCHAR(8),
--     fba                     NUMERIC(24,2),       -- 板上成交额(元)
--     days                    INT,                 -- 连续跌停天数
--     oc                      INT,                 -- 开板次数
--     amount                  NUMERIC(24,2),
--     ltsz                    NUMERIC(24,2),
--     tshare                  NUMERIC(24,2),
--     turnover_rate           NUMERIC(10,4),
--     board_code              VARCHAR(16),
--     data_source             SMALLINT NOT NULL DEFAULT 0,
--     src_detail              VARCHAR(256),
--     create_date             DATE,
--     update_date             TIMESTAMP,
--     PRIMARY KEY (ts_code, trade_date, data_source)
-- );
-- COMMENT ON TABLE limit_down_pool IS '跌停池（LIMIT_DOWN 任务产出）';
--
-- -- 8. 炸板池
-- CREATE TABLE IF NOT EXISTS zhaban_pool (
--     trade_date              DATE NOT NULL,
--     ts_code                 VARCHAR(16) NOT NULL,
--     stock_name              VARCHAR(64),
--     latest_price            NUMERIC(12,2),
--     pct_chg                 NUMERIC(10,4),
--     ztp                     NUMERIC(12,2),       -- 涨停价(元) = ztp/100
--     zf                      NUMERIC(10,4),       -- 振幅%
--     zs                      NUMERIC(10,4),       -- 涨速%（炸板池语义）
--     open_time               VARCHAR(8),
--     open_times              INT,                 -- 炸板次数 = zbc
--     amount                  NUMERIC(24,2),
--     ltsz                    NUMERIC(24,2),
--     tshare                  NUMERIC(24,2),
--     turnover_rate           NUMERIC(10,4),
--     board_code              VARCHAR(16),
--     zttj_ct                 INT,
--     zttj_days               INT,
--     data_source             SMALLINT NOT NULL DEFAULT 0,
--     src_detail              VARCHAR(256),
--     create_date             DATE,
--     update_date             TIMESTAMP,
--     PRIMARY KEY (ts_code, trade_date, data_source)
-- );
-- COMMENT ON TABLE zhaban_pool IS '炸板池（LIMIT_ZHABAN 任务产出）';
--
-- -- 9. 强势池
-- CREATE TABLE IF NOT EXISTS strong_pool (
--     trade_date              DATE NOT NULL,
--     ts_code                 VARCHAR(16) NOT NULL,
--     stock_name              VARCHAR(64),
--     latest_price            NUMERIC(12,2),
--     pct_chg                 NUMERIC(10,4),
--     ztp                     NUMERIC(12,2),
--     zs                      NUMERIC(10,4),       -- 涨速%（强势池语义）
--     nh                      SMALLINT,            -- 是否新高（1=是）
--     board_pos               INT,                 -- 连板数
--     lb                      NUMERIC(10,2),       -- 量比
--     amount                  NUMERIC(24,2),
--     ltsz                    NUMERIC(24,2),
--     tshare                  NUMERIC(24,2),
--     turnover_rate           NUMERIC(10,4),
--     board_code              VARCHAR(16),
--     zttj_ct                 INT,
--     zttj_days               INT,
--     data_source             SMALLINT NOT NULL DEFAULT 0,
--     src_detail              VARCHAR(256),
--     create_date             DATE,
--     update_date             TIMESTAMP,
--     PRIMARY KEY (ts_code, trade_date, data_source)
-- );
-- COMMENT ON TABLE strong_pool IS '强势股池（STRONG_POOL 任务产出）';
--
-- -- 10. 次新池
-- CREATE TABLE IF NOT EXISTS cixin_pool (
--     trade_date              DATE NOT NULL,
--     ts_code                 VARCHAR(16) NOT NULL,
--     stock_name              VARCHAR(64),
--     latest_price            NUMERIC(12,2),
--     pct_chg                 NUMERIC(10,4),
--     ztp                     NUMERIC(12,2),
--     ods                     INT,                 -- 开板几日
--     od                      VARCHAR(8),          -- 开板日期
--     ipod                    VARCHAR(8),          -- 上市日期
--     o                       SMALLINT,            -- 是否新高（1=是）
--     nh                      SMALLINT,            -- 新高备用
--     amount                  NUMERIC(24,2),
--     ltsz                    NUMERIC(24,2),
--     tshare                  NUMERIC(24,2),
--     turnover_rate           NUMERIC(10,4),
--     board_code              VARCHAR(16),
--     zttj_ct                 INT,
--     zttj_days               INT,
--     data_source             SMALLINT NOT NULL DEFAULT 0,
--     src_detail              VARCHAR(256),
--     create_date             DATE,
--     update_date             TIMESTAMP,
--     PRIMARY KEY (ts_code, trade_date, data_source)
-- );
-- COMMENT ON TABLE cixin_pool IS '次新股池（CIXIN_POOL 任务产出）';
--
-- -- ============================================================================
-- -- 三、主题表（龙虎榜/资金流/板块个股关联/北向）
-- -- ============================================================================
--
-- -- 11. 龙虎榜（主键 ts_code + trade_date）
-- CREATE TABLE IF NOT EXISTS dragon_tiger (
--     trade_date              DATE NOT NULL,
--     ts_code                 VARCHAR(16) NOT NULL,
--     stock_name              VARCHAR(64),         -- SECURITY_NAME_ABBR
--     reason                  VARCHAR(256),        -- EXPLAIN
--     explanation             VARCHAR(256),        -- EXPLANATION
--     abnormal_type           VARCHAR(64),         -- CHANGE_TYPE
--     net_buy                 NUMERIC(24,2),       -- BILLBOARD_NET_AMT
--     total_buy               NUMERIC(24,2),       -- BILLBOARD_BUY_AMT
--     total_sell              NUMERIC(24,2),       -- BILLBOARD_SELL_AMT
--     billboard_deal_amt      NUMERIC(24,2),
--     accum_amount            NUMERIC(24,2),
--     buy_ratio               NUMERIC(10,4),
--     sell_ratio              NUMERIC(10,4),
--     buy_seat                INTEGER,
--     sell_seat               INTEGER,
--     buy_seat_new            INTEGER,
--     sell_seat_new           INTEGER,
--     change_rate             NUMERIC(10,4),
--     close_price             NUMERIC(12,4),
--     turnoverrate            NUMERIC(10,4),
--     free_market_cap         NUMERIC(24,2),
--     market                  VARCHAR(8),          -- SZ/BJ/SH
--     deal_amount_ratio       NUMERIC(10,4),
--     deal_net_ratio          NUMERIC(10,4),
--     security_inner_code     VARCHAR(16),
--     security_type_code      VARCHAR(8),
--     trade_id                BIGINT,
--     trade_market            VARCHAR(16),
--     trade_market_code       VARCHAR(8),
--     data_source             SMALLINT NOT NULL DEFAULT 0,
--     src_detail              VARCHAR(256),
--     create_date             DATE,
--     update_date             TIMESTAMP,
--     PRIMARY KEY (ts_code, trade_date)
-- );
-- COMMENT ON TABLE dragon_tiger IS '龙虎榜（DRAGON_TIGER 任务产出，datacenter）';
--
-- -- 12. 龙虎榜席位明细（主键 ts_code + trade_date + seat_name）
-- CREATE TABLE IF NOT EXISTS dt_detail (
--     trade_date              DATE NOT NULL,
--     ts_code                 VARCHAR(16) NOT NULL,
--     seat_name               VARCHAR(64) NOT NULL,   -- 席位名称
--     seat_type               VARCHAR(16),            -- 机构/游资/深股通/沪股通/营业部
--     buy                     NUMERIC(24,2),
--     sell                    NUMERIC(24,2),
--     is_institution          SMALLINT,               -- 是否机构
--     is_famous               SMALLINT,               -- 是否知名游资（TODO M6 维护名单）
--     data_source             SMALLINT NOT NULL DEFAULT 0,
--     src_detail              VARCHAR(256),
--     create_date             DATE,
--     update_date             TIMESTAMP,
--     PRIMARY KEY (ts_code, trade_date, seat_name)
-- );
-- COMMENT ON TABLE dt_detail IS '龙虎榜席位明细（DRAGON_TIGER_DETAIL 任务产出）';
--
-- -- 13. 主力资金流（主键 obj_type + ts_code + board_code + index_code + trade_date）
-- CREATE TABLE IF NOT EXISTS main_fund_flow (
--     trade_date              DATE NOT NULL,
--     obj_type                VARCHAR(8) NOT NULL,    -- stock / board / index
--     ts_code                 VARCHAR(16) NOT NULL,   -- 个股级
--     board_code              VARCHAR(16) NOT NULL,   -- 板块级
--     index_code              VARCHAR(16) NOT NULL,   -- 指数级
--     main_net                NUMERIC(24,2),          -- 主力净流入(元)
--     super_big               NUMERIC(24,2),          -- 超大单净流入
--     big_net                 NUMERIC(24,2),          -- 大单净流入
--     mid_net                 NUMERIC(24,2),          -- 中单净流入
--     small_net               NUMERIC(24,2),          -- 小单净流入
--     data_source             SMALLINT NOT NULL DEFAULT 0,
--     src_detail              VARCHAR(256),
--     create_date             DATE,
--     update_date             TIMESTAMP,
--     PRIMARY KEY (obj_type, ts_code, board_code, index_code, trade_date)
-- );
-- COMMENT ON TABLE main_fund_flow IS '主力资金流（MAIN_FUND_STOCK/MAIN_FUND_BOARD 任务产出）';
-- COMMENT ON COLUMN main_fund_flow.obj_type IS 'stock个股 board板块 index指数';
--
-- -- 14. 板块-个股关联（STOCK_BY_BOARD 任务产出）
-- CREATE TABLE IF NOT EXISTS stock_board_rel (
--     id                      BIGSERIAL PRIMARY KEY,
--     trade_date              DATE NOT NULL,
--     plate_code              VARCHAR(20) NOT NULL,   -- 板块代码(如 BK0450)
--     plate_name              VARCHAR(64),
--     plate_type              SMALLINT,               -- 1地域 2行业 3概念
--     ts_code                 VARCHAR(12) NOT NULL,   -- 股票代码(如 600000.SH)
--     stock_name              VARCHAR(64),
--     data_source             SMALLINT NOT NULL DEFAULT 0,
--     create_date             DATE DEFAULT CURRENT_DATE,
--     update_date             TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
--     UNIQUE (plate_code, ts_code, trade_date)
-- );
-- COMMENT ON TABLE stock_board_rel IS '板块-个股关系（STOCK_BY_BOARD 任务产出）';
-- COMMENT ON COLUMN stock_board_rel.plate_type IS '1地域 2行业 3概念';
--
-- -- 15. 北向资金（主键 trade_date）
-- CREATE TABLE IF NOT EXISTS northbound_flow (
--     trade_date              DATE NOT NULL,
--     hk_hold_net             NUMERIC(24,2),          -- 北向净买入(元)
--     sh_net                  NUMERIC(24,2),          -- 沪股通净买入
--     sz_net                  NUMERIC(24,2),          -- 深股通净买入
--     source                  SMALLINT NOT NULL DEFAULT 0,   -- 0东财 1同花顺
--     create_date             DATE,
--     update_date             DATE,
--     PRIMARY KEY (trade_date)
-- );
-- COMMENT ON TABLE northbound_flow IS '北向资金（northbound_flow 任务产出）';
--
-- -- ============================================================================
-- -- 四、基础设施表（crawl 调度系统）
-- -- ============================================================================
--
-- -- 16. 爬取任务（主键 task_id，唯一键 unique_key）
-- CREATE TABLE IF NOT EXISTS crawl_task (
--     task_id                 BIGSERIAL PRIMARY KEY,
--     task_type               VARCHAR(32) NOT NULL,
--     source                  SMALLINT NOT NULL DEFAULT 0,   -- 0同花顺 1东财
--     url                     TEXT,
--     params_json             TEXT,
--     status                  VARCHAR(16) NOT NULL DEFAULT 'PENDING',
--     priority                SMALLINT DEFAULT 5,
--     retry_count             SMALLINT DEFAULT 0,
--     max_retry               SMALLINT DEFAULT 3,
--     next_retry_at           TIMESTAMP,
--     last_node               VARCHAR(64),
--     started_at              TIMESTAMP,
--     finished_at             TIMESTAMP,
--     duration_ms             BIGINT,
--     unique_key              VARCHAR(128),
--     checkpoint              TEXT,
--     expected_count          INTEGER,
--     actual_count            INTEGER,
--     error_msg               TEXT,
--     created_at              TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
--     updated_at              TIMESTAMP DEFAULT CURRENT_TIMESTAMP
-- );
-- COMMENT ON TABLE crawl_task IS '爬取任务（worker 认领执行）';
-- CREATE INDEX IF NOT EXISTS idx_ct_status ON crawl_task(status);
-- CREATE INDEX IF NOT EXISTS idx_ct_unique_key ON crawl_task(unique_key);
-- CREATE INDEX IF NOT EXISTS idx_ct_task_type ON crawl_task(task_type);
--
-- -- 17. 爬取日志（每 task 执行一次一条）
-- CREATE TABLE IF NOT EXISTS crawl_log (
--     log_id                  BIGSERIAL PRIMARY KEY,
--     task_id                 BIGINT NOT NULL,
--     node                    VARCHAR(64),
--     url                     TEXT,
--     started_at              TIMESTAMP,
--     finished_at             TIMESTAMP,
--     duration_ms             BIGINT,
--     http_status             INTEGER,
--     parse_rows              INTEGER,                 -- parser 实际抽出行数
--     result_status           VARCHAR(16),             -- SUCCESS / FAIL / RETRY
--     raw                     TEXT,                    -- 响应体末页（排错用）
--     bytes                   BIGINT,
--     error_msg               TEXT,
--     created_at              TIMESTAMP DEFAULT CURRENT_TIMESTAMP
-- );
-- COMMENT ON TABLE crawl_log IS '爬取日志（每 task 执行一次一条，含原始响应）';
-- CREATE INDEX IF NOT EXISTS idx_cl_task_id ON crawl_log(task_id);
-- CREATE INDEX IF NOT EXISTS idx_cl_result ON crawl_log(result_status);
--
-- -- 18. 告警（量校验/异常）
-- CREATE TABLE IF NOT EXISTS crawl_alert (
--     alert_id                BIGSERIAL PRIMARY KEY,
--     alert_type              VARCHAR(32) NOT NULL,    -- VOLUME_DEVIATION 等
--     task_id                 BIGINT,
--     task_type               VARCHAR(32),
--     trade_date              DATE,
--     source                  SMALLINT,
--     severity                VARCHAR(8),              -- WARN / ERROR
--     message                 TEXT,
--     value_actual            NUMERIC(20,2),
--     value_expected          NUMERIC(20,2),
--     resolved                SMALLINT DEFAULT 0,
--     created_at              TIMESTAMP DEFAULT CURRENT_TIMESTAMP
-- );
-- COMMENT ON TABLE crawl_alert IS '告警（量校验/异常）';
-- CREATE INDEX IF NOT EXISTS idx_ca_resolved ON crawl_alert(resolved);
-- CREATE INDEX IF NOT EXISTS idx_ca_task_id ON crawl_alert(task_id);
--
-- -- 19. 工作节点（worker 注册）
-- CREATE TABLE IF NOT EXISTS crawl_node (
--     node_id                 VARCHAR(64) PRIMARY KEY,     -- 节点标识（手动指定）
--     node_name               VARCHAR(64),
--     ip                      VARCHAR(64),
--     role                    VARCHAR(16),
--     status                  VARCHAR(16),
--     last_heartbeat          TIMESTAMP,
--     running_tasks           INTEGER DEFAULT 0,
--     created_at              TIMESTAMP DEFAULT CURRENT_TIMESTAMP
-- );
-- COMMENT ON TABLE crawl_node IS '工作节点（worker 注册心跳）';
-- CREATE INDEX IF NOT EXISTS idx_cn_status ON crawl_node(status);
--
-- -- ============================================================================
-- -- 五、索引补充（业务查询加速）
-- -- ============================================================================
--
-- -- 行情表常用查询路径
-- CREATE INDEX IF NOT EXISTS idx_sd_trade_date ON stock_daily(trade_date);
-- CREATE INDEX IF NOT EXISTS idx_sw_trade_date ON stock_weekly(trade_date);
-- CREATE INDEX IF NOT EXISTS idx_id_trade_date ON index_daily(trade_date);
-- CREATE INDEX IF NOT EXISTS idx_bd_trade_date ON board_daily(trade_date);
-- CREATE INDEX IF NOT EXISTS idx_bd_board_type ON board_daily(board_type);
-- CREATE INDEX IF NOT EXISTS idx_dt_trade_date ON dragon_tiger(trade_date);
-- CREATE INDEX IF NOT EXISTS idx_mff_trade_date ON main_fund_flow(trade_date);
-- CREATE INDEX IF NOT EXISTS idx_mff_obj_type ON main_fund_flow(obj_type);
--
-- -- 池表查询路径
-- CREATE INDEX IF NOT EXISTS idx_lup_trade_date ON limit_up_pool(trade_date);
-- CREATE INDEX IF NOT EXISTS idx_ldp_trade_date ON limit_down_pool(trade_date);
-- CREATE INDEX IF NOT EXISTS idx_zp_trade_date ON zhaban_pool(trade_date);
-- CREATE INDEX IF NOT EXISTS idx_sp_trade_date ON strong_pool(trade_date);
-- CREATE INDEX IF NOT EXISTS idx_cp_trade_date ON cixin_pool(trade_date);
--
-- -- ============================================================================
-- -- 附：与本 SQL 配套的说明
-- -- ============================================================================
-- -- 1. 本文件幂等（全部 IF NOT EXISTS），可重复执行
-- -- 2. 旧迁移文件（schema-update-fields.sql / schema-update-v2.sql 等）是增量历史，
-- --    已建库后勿再跑（会因 IF NOT EXISTS 跳过而无害，但无意义）
-- -- 3. board_basic 维表由 BoardBasicSyncService 在 board_daily 落库时副作用维护，
-- --    不再需要独立 maintain 步骤
-- -- 4. 若需 COMMENT 详情，参见 schema-comments.sql（旧版，字段可能不全）
-- -- ============================================================================e）
CREATE TABLE IF NOT EXISTS stock_weekly (
    trade_date              DATE NOT NULL,
    ts_code                 VARCHAR(16) NOT NULL,
    stock_name              VARCHAR(64),
    open                    NUMERIC(12,4),
    high                    NUMERIC(12,4),
    low                     NUMERIC(12,4),
    close                   NUMERIC(12,4),
    vol                     NUMERIC(20,4),
    amount                  NUMERIC(20,4),
    chg_amount              NUMERIC(12,4),
    amplitude               NUMERIC(10,4),
    volume_ratio            NUMERIC(10,4),
    avg_price               NUMERIC(12,4),
    main_net                NUMERIC(24,2),
    pe_static               NUMERIC(12,4),
    leader_code             VARCHAR(16),
    industry_code           VARCHAR(16),
    concept_code            VARCHAR(16),
    market_code             SMALLINT,
    data_source             SMALLINT NOT NULL DEFAULT 0,
    src_detail              VARCHAR(256),
    create_date             DATE,
    update_date             TIMESTAMP,
    PRIMARY KEY (ts_code, trade_date)
);
COMMENT ON TABLE stock_weekly IS '个股周线（STOCK_WEEKLY 任务产出，push2his kline）';

-- 3. 指数日线（主键 index_code + trade_date）
CREATE TABLE IF NOT EXISTS index_daily (
    trade_date              DATE NOT NULL,
    index_code              VARCHAR(16) NOT NULL,   -- 如 000001.SH
    index_name              VARCHAR(64),
    open                    NUMERIC(12,4),
    high                    NUMERIC(12,4),
    low                     NUMERIC(12,4),
    close                   NUMERIC(12,4),
    pre_close               NUMERIC(12,4),
    pct_chg                 NUMERIC(10,4),
    vol                     NUMERIC(20,4),
    amount                  NUMERIC(20,4),
    turnover                NUMERIC(10,4),
    data_source             SMALLINT NOT NULL DEFAULT 0,
    src_detail              VARCHAR(256),
    create_date             DATE,
    update_date             TIMESTAMP,
    PRIMARY KEY (index_code, trade_date)
);
COMMENT ON TABLE index_daily IS '指数日线（INDEX_DAILY 任务产出）';

-- 4. 板块日线（主键 board_code + trade_date）
--    board_type: 1地域 2行业 3概念（来自 taskType 映射，响应无区分字段）
CREATE TABLE IF NOT EXISTS board_daily (
    trade_date              DATE NOT NULL,
    board_code              VARCHAR(32) NOT NULL,
    board_name              VARCHAR(128),
    board_type              SMALLINT,            -- 1地域 2行业 3概念
    pct_chg                 NUMERIC(10,4),
    amount                  NUMERIC(20,4),
    up_count                INTEGER,
    down_count              INTEGER,
    limit_up_count          INTEGER,             -- TODO M6：clist 无直接字段
    leading_code            VARCHAR(32),         -- f140 领涨股代码
    leading_name            VARCHAR(64),         -- f128 领涨股名称
    main_net                NUMERIC(20,4),       -- f62  主力净流入
    board_code2             VARCHAR(32),         -- TODO M6：接口未返回
    data_source             SMALLINT NOT NULL DEFAULT 0,
    src_detail              TEXT,
    price                   NUMERIC(10,4),       -- f2  板块最新价
    rise_fall               NUMERIC(10,4),       -- f4  涨跌额
    volume                  NUMERIC(20,4),       -- f5  成交量(手)
    amplitude               NUMERIC(10,4),       -- f7  振幅%
    high_price              NUMERIC(10,4),       -- f15 最高价
    low_price               NUMERIC(10,4),       -- f16 最低价
    today_open_price        NUMERIC(10,4),       -- f17 今开
    yesterday_received_price NUMERIC(10,4),      -- f18 昨收
    volume_ratio            NUMERIC(10,4),       -- f10 量比
    turnover_ratio          NUMERIC(10,4),       -- f8  换手率%
    total_market_value      NUMERIC(20,4),       -- f20 总市值
    circulation_market_value NUMERIC(20,4),      -- f21 流通市值
    create_date             DATE,
    update_date             TIMESTAMP,
    PRIMARY KEY (board_code, trade_date)
);
COMMENT ON TABLE board_daily IS '板块日线（REGION/INDUSTRY/CONCEPT_DAILY 任务产出）';
COMMENT ON COLUMN board_daily.board_type IS '1地域 2行业 3概念（来自 taskType 映射）';

-- 5. 板块基础数据（维表，主键 id；唯一校验 board_type+board_code+data_source）
CREATE TABLE IF NOT EXISTS board_basic (
    id                      BIGSERIAL PRIMARY KEY,
    board_type              SMALLINT NOT NULL,   -- 1地域 2行业 3概念
    code                    VARCHAR(16),         -- 同花顺板块指数代码
    board_code              VARCHAR(16) NOT NULL,
    board_name              VARCHAR(64) NOT NULL,
    features                VARCHAR(340),
    status                  SMALLINT DEFAULT 1,  -- 1正常 0删除
    data_source             SMALLINT NOT NULL DEFAULT 0,
    create_date             DATE NOT NULL DEFAULT CURRENT_DATE,
    update_date             TIMESTAMP
);
COMMENT ON TABLE board_basic IS '板块基础数据表（board_daily 同步副作用维护）';
CREATE INDEX IF NOT EXISTS idx_bb_board_code ON board_basic(board_code);
CREATE INDEX IF NOT EXISTS idx_bb_board_type ON board_basic(board_type);

-- ============================================================================
-- 二、池表（涨跌停/炸板/强势/次新）
--    主键：(ts_code, trade_date, data_source)
-- ============================================================================

-- 6. 涨停池
CREATE TABLE IF NOT EXISTS limit_up_pool (
    trade_date              DATE NOT NULL,
    ts_code                 VARCHAR(16) NOT NULL,
    stock_name              VARCHAR(64),
    latest_price            NUMERIC(12,2),       -- 最新价(元) = p/100
    pct_chg                 NUMERIC(10,4),       -- 涨跌幅%
    board_pos               INT,                 -- 连板数 = lbc
    is_first                SMALLINT DEFAULT 0,  -- 是否首板（lbc=1）
    is_continuous           SMALLINT DEFAULT 0,  -- 是否连板（lbc>=2）
    limit_style             VARCHAR(10),         -- 一字/换手
    open_time               VARCHAR(8),          -- 首次封板 HH:mm:ss = fbt
    last_time               VARCHAR(8),          -- 最后封板 HH:mm:ss = lbt
    open_times              INT,                 -- 开板次数 = zbc
    fund                    NUMERIC(24,2),       -- 封单资金(元)
    amount                  NUMERIC(24,2),       -- 成交额(元)
    ltsz                    NUMERIC(24,2),       -- 流通市值(元)
    tshare                  NUMERIC(24,2),       -- 总市值(元)
    turnover_rate           NUMERIC(10,4),       -- 换手率%
    board_code              VARCHAR(16),         -- 行业代码 = hybk
    zttj_ct                 INT,                 -- 涨停次数统计
    zttj_days               INT,                 -- 连板天数
    data_source             SMALLINT NOT NULL DEFAULT 0,
    src_detail              VARCHAR(256),
    create_date             DATE,
    update_date             TIMESTAMP,
    PRIMARY KEY (ts_code, trade_date, data_source)
);
COMMENT ON TABLE limit_up_pool IS '涨停池（LIMIT_UP 任务产出）';

-- 7. 跌停池
CREATE TABLE IF NOT EXISTS limit_down_pool (
    trade_date              DATE NOT NULL,
    ts_code                 VARCHAR(16) NOT NULL,
    stock_name              VARCHAR(64),
    latest_price            NUMERIC(12,2),
    pct_chg                 NUMERIC(10,4),
    pe                      NUMERIC(10,2),       -- 动态市盈率
    fund                    NUMERIC(24,2),
    last_time               VARCHAR(8),
    fba                     NUMERIC(24,2),       -- 板上成交额(元)
    days                    INT,                 -- 连续跌停天数
    oc                      INT,                 -- 开板次数
    amount                  NUMERIC(24,2),
    ltsz                    NUMERIC(24,2),
    tshare                  NUMERIC(24,2),
    turnover_rate           NUMERIC(10,4),
    board_code              VARCHAR(16),
    data_source             SMALLINT NOT NULL DEFAULT 0,
    src_detail              VARCHAR(256),
    create_date             DATE,
    update_date             TIMESTAMP,
    PRIMARY KEY (ts_code, trade_date, data_source)
);
COMMENT ON TABLE limit_down_pool IS '跌停池（LIMIT_DOWN 任务产出）';

-- 8. 炸板池
CREATE TABLE IF NOT EXISTS zhaban_pool (
    trade_date              DATE NOT NULL,
    ts_code                 VARCHAR(16) NOT NULL,
    stock_name              VARCHAR(64),
    latest_price            NUMERIC(12,2),
    pct_chg                 NUMERIC(10,4),
    ztp                     NUMERIC(12,2),       -- 涨停价(元) = ztp/100
    zf                      NUMERIC(10,4),       -- 振幅%
    zs                      NUMERIC(10,4),       -- 涨速%（炸板池语义）
    open_time               VARCHAR(8),
    open_times              INT,                 -- 炸板次数 = zbc
    amount                  NUMERIC(24,2),
    ltsz                    NUMERIC(24,2),
    tshare                  NUMERIC(24,2),
    turnover_rate           NUMERIC(10,4),
    board_code              VARCHAR(16),
    zttj_ct                 INT,
    zttj_days               INT,
    data_source             SMALLINT NOT NULL DEFAULT 0,
    src_detail              VARCHAR(256),
    create_date             DATE,
    update_date             TIMESTAMP,
    PRIMARY KEY (ts_code, trade_date, data_source)
);
COMMENT ON TABLE zhaban_pool IS '炸板池（LIMIT_ZHABAN 任务产出）';

-- 9. 强势池
CREATE TABLE IF NOT EXISTS strong_pool (
    trade_date              DATE NOT NULL,
    ts_code                 VARCHAR(16) NOT NULL,
    stock_name              VARCHAR(64),
    latest_price            NUMERIC(12,2),
    pct_chg                 NUMERIC(10,4),
    ztp                     NUMERIC(12,2),
    zs                      NUMERIC(10,4),       -- 涨速%（强势池语义）
    nh                      SMALLINT,            -- 是否新高（1=是）
    board_pos               INT,                 -- 连板数
    lb                      NUMERIC(10,2),       -- 量比
    amount                  NUMERIC(24,2),
    ltsz                    NUMERIC(24,2),
    tshare                  NUMERIC(24,2),
    turnover_rate           NUMERIC(10,4),
    board_code              VARCHAR(16),
    zttj_ct                 INT,
    zttj_days               INT,
    data_source             SMALLINT NOT NULL DEFAULT 0,
    src_detail              VARCHAR(256),
    create_date             DATE,
    update_date             TIMESTAMP,
    PRIMARY KEY (ts_code, trade_date, data_source)
);
COMMENT ON TABLE strong_pool IS '强势股池（STRONG_POOL 任务产出）';

-- 10. 次新池
CREATE TABLE IF NOT EXISTS cixin_pool (
    trade_date              DATE NOT NULL,
    ts_code                 VARCHAR(16) NOT NULL,
    stock_name              VARCHAR(64),
    latest_price            NUMERIC(12,2),
    pct_chg                 NUMERIC(10,4),
    ztp                     NUMERIC(12,2),
    ods                     INT,                 -- 开板几日
    od                      VARCHAR(8),          -- 开板日期
    ipod                    VARCHAR(8),          -- 上市日期
    o                       SMALLINT,            -- 是否新高（1=是）
    nh                      SMALLINT,            -- 新高备用
    amount                  NUMERIC(24,2),
    ltsz                    NUMERIC(24,2),
    tshare                  NUMERIC(24,2),
    turnover_rate           NUMERIC(10,4),
    board_code              VARCHAR(16),
    zttj_ct                 INT,
    zttj_days               INT,
    data_source             SMALLINT NOT NULL DEFAULT 0,
    src_detail              VARCHAR(256),
    create_date             DATE,
    update_date             TIMESTAMP,
    PRIMARY KEY (ts_code, trade_date, data_source)
);
COMMENT ON TABLE cixin_pool IS '次新股池（CIXIN_POOL 任务产出）';

-- ============================================================================
-- 三、主题表（龙虎榜/资金流/板块个股关联/北向）
-- ============================================================================

-- 11. 龙虎榜（主键 ts_code + trade_date）
CREATE TABLE IF NOT EXISTS dragon_tiger (
    trade_date              DATE NOT NULL,
    ts_code                 VARCHAR(16) NOT NULL,
    stock_name              VARCHAR(64),         -- SECURITY_NAME_ABBR
    reason                  VARCHAR(256),        -- EXPLAIN
    explanation             VARCHAR(256),        -- EXPLANATION
    abnormal_type           VARCHAR(64),         -- CHANGE_TYPE
    net_buy                 NUMERIC(24,2),       -- BILLBOARD_NET_AMT
    total_buy               NUMERIC(24,2),       -- BILLBOARD_BUY_AMT
    total_sell              NUMERIC(24,2),       -- BILLBOARD_SELL_AMT
    billboard_deal_amt      NUMERIC(24,2),
    accum_amount            NUMERIC(24,2),
    buy_ratio               NUMERIC(10,4),
    sell_ratio              NUMERIC(10,4),
    buy_seat                INTEGER,
    sell_seat               INTEGER,
    buy_seat_new            INTEGER,
    sell_seat_new           INTEGER,
    change_rate             NUMERIC(10,4),
    close_price             NUMERIC(12,4),
    turnoverrate            NUMERIC(10,4),
    free_market_cap         NUMERIC(24,2),
    market                  VARCHAR(8),          -- SZ/BJ/SH
    deal_amount_ratio       NUMERIC(10,4),
    deal_net_ratio          NUMERIC(10,4),
    security_inner_code     VARCHAR(16),
    security_type_code      VARCHAR(8),
    trade_id                BIGINT,
    trade_market            VARCHAR(16),
    trade_market_code       VARCHAR(8),
    data_source             SMALLINT NOT NULL DEFAULT 0,
    src_detail              VARCHAR(256),
    create_date             DATE,
    update_date             TIMESTAMP,
    PRIMARY KEY (ts_code, trade_date)
);
COMMENT ON TABLE dragon_tiger IS '龙虎榜（DRAGON_TIGER 任务产出，datacenter）';

-- 12. 龙虎榜席位明细（主键 ts_code + trade_date + seat_name）
CREATE TABLE IF NOT EXISTS dt_detail (
    trade_date              DATE NOT NULL,
    ts_code                 VARCHAR(16) NOT NULL,
    seat_name               VARCHAR(64) NOT NULL,   -- 席位名称
    seat_type               VARCHAR(16),            -- 机构/游资/深股通/沪股通/营业部
    buy                     NUMERIC(24,2),
    sell                    NUMERIC(24,2),
    is_institution          SMALLINT,               -- 是否机构
    is_famous               SMALLINT,               -- 是否知名游资（TODO M6 维护名单）
    data_source             SMALLINT NOT NULL DEFAULT 0,
    src_detail              VARCHAR(256),
    create_date             DATE,
    update_date             TIMESTAMP,
    PRIMARY KEY (ts_code, trade_date, seat_name)
);
COMMENT ON TABLE dt_detail IS '龙虎榜席位明细（DRAGON_TIGER_DETAIL 任务产出）';

-- 13. 主力资金流（主键 obj_type + ts_code + board_code + index_code + trade_date）
CREATE TABLE IF NOT EXISTS main_fund_flow (
    trade_date              DATE NOT NULL,
    obj_type                VARCHAR(8) NOT NULL,    -- stock / board / index
    ts_code                 VARCHAR(16) NOT NULL,   -- 个股级
    board_code              VARCHAR(16) NOT NULL,   -- 板块级
    index_code              VARCHAR(16) NOT NULL,   -- 指数级
    main_net                NUMERIC(24,2),          -- 主力净流入(元)
    super_big               NUMERIC(24,2),          -- 超大单净流入
    big_net                 NUMERIC(24,2),          -- 大单净流入
    mid_net                 NUMERIC(24,2),          -- 中单净流入
    small_net               NUMERIC(24,2),          -- 小单净流入
    data_source             SMALLINT NOT NULL DEFAULT 0,
    src_detail              VARCHAR(256),
    create_date             DATE,
    update_date             TIMESTAMP,
    PRIMARY KEY (obj_type, ts_code, board_code, index_code, trade_date)
);
COMMENT ON TABLE main_fund_flow IS '主力资金流（MAIN_FUND_STOCK/MAIN_FUND_BOARD 任务产出）';
COMMENT ON COLUMN main_fund_flow.obj_type IS 'stock个股 board板块 index指数';

-- 14. 板块-个股关联（STOCK_BY_BOARD 任务产出）
CREATE TABLE IF NOT EXISTS stock_board_rel (
    id                      BIGSERIAL PRIMARY KEY,
    trade_date              DATE NOT NULL,
    plate_code              VARCHAR(20) NOT NULL,   -- 板块代码(如 BK0450)
    plate_name              VARCHAR(64),
    plate_type              SMALLINT,               -- 1地域 2行业 3概念
    ts_code                 VARCHAR(12) NOT NULL,   -- 股票代码(如 600000.SH)
    stock_name              VARCHAR(64),
    data_source             SMALLINT NOT NULL DEFAULT 0,
    create_date             DATE DEFAULT CURRENT_DATE,
    update_date             TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (plate_code, ts_code, trade_date)
);
COMMENT ON TABLE stock_board_rel IS '板块-个股关系（STOCK_BY_BOARD 任务产出）';
COMMENT ON COLUMN stock_board_rel.plate_type IS '1地域 2行业 3概念';

-- 15. 北向资金（主键 trade_date）
CREATE TABLE IF NOT EXISTS northbound_flow (
    trade_date              DATE NOT NULL,
    hk_hold_net             NUMERIC(24,2),          -- 北向净买入(元)
    sh_net                  NUMERIC(24,2),          -- 沪股通净买入
    sz_net                  NUMERIC(24,2),          -- 深股通净买入
    source                  SMALLINT NOT NULL DEFAULT 0,   -- 0东财 1同花顺
    create_date             DATE,
    update_date             DATE,
    PRIMARY KEY (trade_date)
);
COMMENT ON TABLE northbound_flow IS '北向资金（northbound_flow 任务产出）';

-- ============================================================================
-- 四、基础设施表（crawl 调度系统）
-- ============================================================================

-- 16. 爬取任务（主键 task_id，唯一键 unique_key）
CREATE TABLE IF NOT EXISTS crawl_task (
    task_id                 BIGSERIAL PRIMARY KEY,
    task_type               VARCHAR(32) NOT NULL,
    source                  SMALLINT NOT NULL DEFAULT 0,   -- 0同花顺 1东财
    url                     TEXT,
    params_json             TEXT,
    status                  VARCHAR(16) NOT NULL DEFAULT 'PENDING',
    priority                SMALLINT DEFAULT 5,
    retry_count             SMALLINT DEFAULT 0,
    max_retry               SMALLINT DEFAULT 3,
    next_retry_at           TIMESTAMP,
    last_node               VARCHAR(64),
    started_at              TIMESTAMP,
    finished_at             TIMESTAMP,
    duration_ms             BIGINT,
    unique_key              VARCHAR(128),
    checkpoint              TEXT,
    expected_count          INTEGER,
    actual_count            INTEGER,
    error_msg               TEXT,
    created_at              TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at              TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);
COMMENT ON TABLE crawl_task IS '爬取任务（worker 认领执行）';
CREATE INDEX IF NOT EXISTS idx_ct_status ON crawl_task(status);
CREATE INDEX IF NOT EXISTS idx_ct_unique_key ON crawl_task(unique_key);
CREATE INDEX IF NOT EXISTS idx_ct_task_type ON crawl_task(task_type);

-- 17. 爬取日志（每 task 执行一次一条）
CREATE TABLE IF NOT EXISTS crawl_log (
    log_id                  BIGSERIAL PRIMARY KEY,
    task_id                 BIGINT NOT NULL,
    node                    VARCHAR(64),
    url                     TEXT,
    started_at              TIMESTAMP,
    finished_at             TIMESTAMP,
    duration_ms             BIGINT,
    http_status             INTEGER,
    parse_rows              INTEGER,                 -- parser 实际抽出行数
    result_status           VARCHAR(16),             -- SUCCESS / FAIL / RETRY
    raw                     TEXT,                    -- 响应体末页（排错用）
    bytes                   BIGINT,
    error_msg               TEXT,
    created_at              TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);
COMMENT ON TABLE crawl_log IS '爬取日志（每 task 执行一次一条，含原始响应）';
CREATE INDEX IF NOT EXISTS idx_cl_task_id ON crawl_log(task_id);
CREATE INDEX IF NOT EXISTS idx_cl_result ON crawl_log(result_status);

-- 18. 告警（量校验/异常）
CREATE TABLE IF NOT EXISTS crawl_alert (
    alert_id                BIGSERIAL PRIMARY KEY,
    alert_type              VARCHAR(32) NOT NULL,    -- VOLUME_DEVIATION 等
    task_id                 BIGINT,
    task_type               VARCHAR(32),
    trade_date              DATE,
    source                  SMALLINT,
    severity                VARCHAR(8),              -- WARN / ERROR
    message                 TEXT,
    value_actual            NUMERIC(20,2),
    value_expected          NUMERIC(20,2),
    resolved                SMALLINT DEFAULT 0,
    created_at              TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);
COMMENT ON TABLE crawl_alert IS '告警（量校验/异常）';
CREATE INDEX IF NOT EXISTS idx_ca_resolved ON crawl_alert(resolved);
CREATE INDEX IF NOT EXISTS idx_ca_task_id ON crawl_alert(task_id);

-- 19. 工作节点（worker 注册）
CREATE TABLE IF NOT EXISTS crawl_node (
    node_id                 VARCHAR(64) PRIMARY KEY,     -- 节点标识（手动指定）
    node_name               VARCHAR(64),
    ip                      VARCHAR(64),
    role                    VARCHAR(16),
    status                  VARCHAR(16),
    last_heartbeat          TIMESTAMP,
    running_tasks           INTEGER DEFAULT 0,
    created_at              TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);
COMMENT ON TABLE crawl_node IS '工作节点（worker 注册心跳）';
CREATE INDEX IF NOT EXISTS idx_cn_status ON crawl_node(status);

-- ============================================================================
-- 五、索引补充（业务查询加速）
-- ============================================================================

-- 行情表常用查询路径
CREATE INDEX IF NOT EXISTS idx_sd_trade_date ON stock_daily(trade_date);
CREATE INDEX IF NOT EXISTS idx_sw_trade_date ON stock_weekly(trade_date);
CREATE INDEX IF NOT EXISTS idx_id_trade_date ON index_daily(trade_date);
CREATE INDEX IF NOT EXISTS idx_bd_trade_date ON board_daily(trade_date);
CREATE INDEX IF NOT EXISTS idx_bd_board_type ON board_daily(board_type);
CREATE INDEX IF NOT EXISTS idx_dt_trade_date ON dragon_tiger(trade_date);
CREATE INDEX IF NOT EXISTS idx_mff_trade_date ON main_fund_flow(trade_date);
CREATE INDEX IF NOT EXISTS idx_mff_obj_type ON main_fund_flow(obj_type);

-- 池表查询路径
CREATE INDEX IF NOT EXISTS idx_lup_trade_date ON limit_up_pool(trade_date);
CREATE INDEX IF NOT EXISTS idx_ldp_trade_date ON limit_down_pool(trade_date);
CREATE INDEX IF NOT EXISTS idx_zp_trade_date ON zhaban_pool(trade_date);
CREATE INDEX IF NOT EXISTS idx_sp_trade_date ON strong_pool(trade_date);
CREATE INDEX IF NOT EXISTS idx_cp_trade_date ON cixin_pool(trade_date);

-- ============================================================================
-- 六、辅助表（概念/财闻/交易日志/交易日历）
--    注：这 5 张表无独立 mapper（访问少），按字段语义推主键
-- ============================================================================

-- 20. 概念主题（concept）
CREATE TABLE IF NOT EXISTS concept (
    theme_code              VARCHAR(32) NOT NULL,        -- 主题代码
    theme_name              VARCHAR(64),
    theme_type              VARCHAR(16),                 -- 概念 / 行业 / 地域
    scarcity                NUMERIC(5,4),                -- 稀缺性 0~1
    imagination             NUMERIC(5,4),                -- 想象空间 0~1
    source                  SMALLINT DEFAULT 0,          -- 0东财 1同花顺
    create_date             DATE,
    update_date             DATE,
    PRIMARY KEY (theme_code)
);
COMMENT ON TABLE concept IS '概念主题库';
COMMENT ON COLUMN concept.scarcity IS '稀缺性 0~1';
COMMENT ON COLUMN concept.imagination IS '想象空间 0~1';

-- 21. 财务报表（financial，主键 ts_code + end_date）
CREATE TABLE IF NOT EXISTS financial (
    ts_code                 VARCHAR(16) NOT NULL,
    end_date                DATE NOT NULL,               -- 报告期
    report_type             VARCHAR(8),                  -- Q1 / Q2 / Q3 / 年报
    ann_date                DATE,                        -- 公告日期
    revenue                 NUMERIC(24,2),               -- 营收(元)
    net_profit              NUMERIC(24,2),               -- 净利润(元)
    net_profit_yoy          NUMERIC(10,4),               -- 净利润同比%
    roe                     NUMERIC(10,4),
    source                  SMALLINT DEFAULT 0,
    create_date             DATE,
    update_date             DATE,
    PRIMARY KEY (ts_code, end_date)
);
COMMENT ON TABLE financial IS '财务报表（个股季报/年报）';

-- 22. 新闻事件（news_event，主键 event_id）
CREATE TABLE IF NOT EXISTS news_event (
    event_id                BIGSERIAL PRIMARY KEY,
    event_time              TIMESTAMP,
    title                   VARCHAR(256),
    content                 TEXT,
    source                  VARCHAR(64),
    category                VARCHAR(16),                 -- 政策 / 行业 / 公司 / 题材
    related_board           VARCHAR(256),                -- 关联板块代码(逗号分隔)
    related_ts_code         VARCHAR(256),                -- 关联个股代码(逗号分隔)
    sentiment_score         NUMERIC(5,4),                -- 情感分 -1~1
    is_policy               SMALLINT,                    -- 是否政策
    create_date             DATE,
    update_date             TIMESTAMP
);
COMMENT ON TABLE news_event IS '新闻事件（含情感分/关联板块个股）';
CREATE INDEX IF NOT EXISTS idx_ne_event_time ON news_event(event_time);
CREATE INDEX IF NOT EXISTS idx_ne_category ON news_event(category);

-- 23. 交易日历（trade_calendar，主键 trade_date）
CREATE TABLE IF NOT EXISTS trade_calendar (
    trade_date              DATE NOT NULL,
    is_trading              SMALLINT,                    -- 1=交易日 0=休市
    data_source             SMALLINT NOT NULL DEFAULT 0,
    src_detail              VARCHAR(256),
    create_date             DATE,
    update_date             TIMESTAMP,
    PRIMARY KEY (trade_date)
);
COMMENT ON TABLE trade_calendar IS '交易日历';

-- 24. 交易日志（trade_log，主键 id）
CREATE TABLE IF NOT EXISTS trade_log (
    id                      BIGSERIAL PRIMARY KEY,
    trade_date              DATE,
    ts_code                 VARCHAR(16),
    side                    VARCHAR(8),                  -- buy / sell
    price                   NUMERIC(12,4),
    qty                     NUMERIC(20,4),
    reason                  VARCHAR(256),                -- 买入逻辑
    emotion_tag             VARCHAR(32),                 -- 执行心态标签
    ying_dai                VARCHAR(16),                 -- 买对/买错/未明 三态处置
    data_source             SMALLINT DEFAULT 99,         -- 99=用户手工
    src_detail              VARCHAR(256),
    create_date             DATE,
    update_date             DATE
);
COMMENT ON TABLE trade_log IS '交易日志（用户手工+程序化）';
CREATE INDEX IF NOT EXISTS idx_tl_trade_date ON trade_log(trade_date);
CREATE INDEX IF NOT EXISTS idx_tl_ts_code ON trade_log(ts_code);

-- ============================================================================
-- 附：与本 SQL 配套的说明
-- ============================================================================
-- 1. 本文件幂等（全部 IF NOT EXISTS），可重复执行
-- 2. 旧迁移文件（schema-update-fields.sql / schema-update-v2.sql 等）是增量历史，
--    已建库后勿再跑（会因 IF NOT EXISTS 跳过而无害，但无意义）
-- 3. board_basic 维表由 BoardBasicSyncService 在 board_daily 落库时副作用维护，
--    不再需要独立 maintain 步骤
-- 4. 若需 COMMENT 详情，参见 schema-comments.sql（旧版，字段可能不全）
-- ============================================================================

