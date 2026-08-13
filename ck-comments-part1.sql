-- ============================================================================
-- ClickHouse 全库字段注释（crawler 数据库）
-- 生成时间: 2026-08-13
-- 依据: EastmoneyParsers.java（运行时真值）+ EastmoneyFieldMap.java（f码映射）+ 实体 Javadoc
--
-- 运行: clickhouse-client --host <ck> --port=8123 -d crawler < ck-comments.sql
--
-- 说明:
--   1. 金额单位=元；幅度/涨跌=百分比数值；成交量=手
--   2. "死列"(解析器未写入,实际全NULL)统一注释为"预留字段,暂无数据"
--   3. 6 张计算层表(S1/S2/S4/S6/S7)计算器未实现,字段按命名惯例推断,已标注[推断]
-- ============================================================================

-- ========== 一、辅助/维表 ==========

-- ---------------------------- trade_calendar(交易日历) ----------------------------
ALTER TABLE trade_calendar MODIFY COLUMN trade_date COMMENT '日历日期(主键)';
ALTER TABLE trade_calendar MODIFY COLUMN is_trading COMMENT '是否交易日: 1=交易日 0=休市';
ALTER TABLE trade_calendar MODIFY COLUMN data_source COMMENT '数据来源: 0=东财 1=同花顺';
ALTER TABLE trade_calendar MODIFY COLUMN src_detail COMMENT '来源URL/接口/备注';
ALTER TABLE trade_calendar MODIFY COLUMN create_date COMMENT '入库日期';
ALTER TABLE trade_calendar MODIFY COLUMN update_date COMMENT '更新时间';

-- ---------------------------- board_basic(板块基础维表) ----------------------------
ALTER TABLE board_basic MODIFY COLUMN board_type COMMENT '板块类型: 1=地域 2=行业 3=概念';
ALTER TABLE board_basic MODIFY COLUMN code COMMENT '同花顺板块指数代码(东财来源无此值,暂为空)';
ALTER TABLE board_basic MODIFY COLUMN board_code COMMENT '板块代号(如 BK0450)';
ALTER TABLE board_basic MODIFY COLUMN board_name COMMENT '板块名称';
ALTER TABLE board_basic MODIFY COLUMN features COMMENT '备用字段(暂无内容)';
ALTER TABLE board_basic MODIFY COLUMN status COMMENT '状态: 1=正常(当前固定为1)';
ALTER TABLE board_basic MODIFY COLUMN data_source COMMENT '数据来源: 0=东财 1=同花顺';

-- ---------------------------- stock_board_rel(板块-个股关联) ----------------------------
ALTER TABLE stock_board_rel MODIFY COLUMN ts_code COMMENT '个股代码(带后缀,如 600000.SH)';
ALTER TABLE stock_board_rel MODIFY COLUMN board_code COMMENT '板块代码';
ALTER TABLE stock_board_rel MODIFY COLUMN board_name COMMENT '板块名称';
ALTER TABLE stock_board_rel MODIFY COLUMN stock_name COMMENT '股票名称';
ALTER TABLE stock_board_rel MODIFY COLUMN board_type COMMENT '板块类型: 1=地域 2=行业 3=概念';
ALTER TABLE stock_board_rel MODIFY COLUMN is_leader COMMENT '是否领涨股: 1=是 0=否(当前未填充)';
ALTER TABLE stock_board_rel MODIFY COLUMN is_midarm COMMENT '是否中军股: 1=是 0=否(当前未填充)';
ALTER TABLE stock_board_rel MODIFY COLUMN weight COMMENT '权重(f2,具体口径待确认)';
ALTER TABLE stock_board_rel MODIFY COLUMN effective_date COMMENT '关系生效日期';
ALTER TABLE stock_board_rel MODIFY COLUMN data_source COMMENT '数据来源: 0=东财 1=同花顺';

-- ========== 二、行情核心表 ==========

-- ---------------------------- stock_daily(个股日线) ----------------------------
ALTER TABLE stock_daily MODIFY COLUMN trade_date COMMENT '交易日期';
ALTER TABLE stock_daily MODIFY COLUMN ts_code COMMENT '股票代码(带后缀,如 600000.SH)';
ALTER TABLE stock_daily MODIFY COLUMN stock_name COMMENT '股票名称';
ALTER TABLE stock_daily MODIFY COLUMN open COMMENT '开盘价(f17,元)';
ALTER TABLE stock_daily MODIFY COLUMN high COMMENT '最高价(f15,元)';
ALTER TABLE stock_daily MODIFY COLUMN low COMMENT '最低价(f16,元)';
ALTER TABLE stock_daily MODIFY COLUMN close COMMENT '收盘价(f2,元)';
ALTER TABLE stock_daily MODIFY COLUMN pre_close COMMENT '昨收价(f18,元)';
ALTER TABLE stock_daily MODIFY COLUMN pct_chg COMMENT '涨跌幅(f3,%)';
ALTER TABLE stock_daily MODIFY COLUMN vol COMMENT '成交量(f5,手)';
ALTER TABLE stock_daily MODIFY COLUMN amount COMMENT '成交额(f6,元)';
ALTER TABLE stock_daily MODIFY COLUMN turnover COMMENT '换手率(f8,%)';
ALTER TABLE stock_daily MODIFY COLUMN total_mv COMMENT '总市值(f20,元)';
ALTER TABLE stock_daily MODIFY COLUMN circ_mv COMMENT '流通市值(f21,元)';
ALTER TABLE stock_daily MODIFY COLUMN pe COMMENT '市盈率TTM(f9)';
ALTER TABLE stock_daily MODIFY COLUMN is_limit_up COMMENT '是否涨停(1/0,pct_chg≥9.8近似判定)';
ALTER TABLE stock_daily MODIFY COLUMN is_limit_down COMMENT '是否跌停(1/0,pct_chg≤-9.8近似判定)';
ALTER TABLE stock_daily MODIFY COLUMN chg_amount COMMENT '涨跌额(f4,元)';
ALTER TABLE stock_daily MODIFY COLUMN amplitude COMMENT '振幅(f7,%)';
ALTER TABLE stock_daily MODIFY COLUMN volume_ratio COMMENT '量比(f10)';
ALTER TABLE stock_daily MODIFY COLUMN avg_price COMMENT '均价(预留字段,暂无数据)';
ALTER TABLE stock_daily MODIFY COLUMN main_net COMMENT '主力净流入(f62,元)';
ALTER TABLE stock_daily MODIFY COLUMN super_big COMMENT '超大单净流入(f66,元)';
ALTER TABLE stock_daily MODIFY COLUMN big_net COMMENT '大单净流入(f72,元)';
ALTER TABLE stock_daily MODIFY COLUMN mid_net COMMENT '中单净流入(f78,元)';
ALTER TABLE stock_daily MODIFY COLUMN small_net COMMENT '小单净流入(f84,元)';
ALTER TABLE stock_daily MODIFY COLUMN pe_static COMMENT '静态市盈率(f115)';
ALTER TABLE stock_daily MODIFY COLUMN leader_code COMMENT '领涨股代码(预留字段,暂无数据)';
ALTER TABLE stock_daily MODIFY COLUMN industry_code COMMENT '所属行业代码(预留字段,暂无数据)';
ALTER TABLE stock_daily MODIFY COLUMN concept_code COMMENT '所属概念代码(预留字段,暂无数据)';
ALTER TABLE stock_daily MODIFY COLUMN market_code COMMENT '市场码(f152): 0=深 1=沪 2=京';
ALTER TABLE stock_daily MODIFY COLUMN velocity COMMENT '涨速(f11,%)';
ALTER TABLE stock_daily MODIFY COLUMN turn_speed COMMENT '涨速另一口径(f22,%)';
ALTER TABLE stock_daily MODIFY COLUMN is_new_high COMMENT '是否新高(预留字段,暂无数据)';
ALTER TABLE stock_daily MODIFY COLUMN chg_60d COMMENT '60日涨跌幅(f23,%)';
ALTER TABLE stock_daily MODIFY COLUMN seal_fund COMMENT '封单资金(预留字段,暂无数据)';
ALTER TABLE stock_daily MODIFY COLUMN board_days COMMENT '连板天数(预留字段,暂无数据)';
ALTER TABLE stock_daily MODIFY COLUMN board_stat COMMENT '涨停统计(预留字段,暂无数据)';
ALTER TABLE stock_daily MODIFY COLUMN first_seal_time COMMENT '首次封板时间(预留字段,暂无数据)';
ALTER TABLE stock_daily MODIFY COLUMN last_seal_time COMMENT '最后封板时间(预留字段,暂无数据)';
ALTER TABLE stock_daily MODIFY COLUMN limit_type COMMENT '涨停类型(预留字段,暂无数据)';
ALTER TABLE stock_daily MODIFY COLUMN reserved_f24 COMMENT '年初至今涨跌幅(f24,%)';
ALTER TABLE stock_daily MODIFY COLUMN reserved_f25 COMMENT 'f25(含义待确认)';
ALTER TABLE stock_daily MODIFY COLUMN reserved_f107 COMMENT 'f107(预留字段,暂无数据)';
ALTER TABLE stock_daily MODIFY COLUMN reserved_f136 COMMENT 'f136(预留字段,暂无数据)';
ALTER TABLE stock_daily MODIFY COLUMN reserved_f173 COMMENT '涨速(f173,%)';
ALTER TABLE stock_daily MODIFY COLUMN data_source COMMENT '数据来源: 0=东财 1=同花顺';
ALTER TABLE stock_daily MODIFY COLUMN src_detail COMMENT '来源URL/接口/备注';
ALTER TABLE stock_daily MODIFY COLUMN create_date COMMENT '入库日期';
ALTER TABLE stock_daily MODIFY COLUMN update_date COMMENT '更新时间';

-- ---------------------------- stock_weekly(个股周线) ----------------------------
ALTER TABLE stock_weekly MODIFY COLUMN trade_date COMMENT '交易日期(周末日期)';
ALTER TABLE stock_weekly MODIFY COLUMN ts_code COMMENT '股票代码';
ALTER TABLE stock_weekly MODIFY COLUMN stock_name COMMENT '股票名称';
ALTER TABLE stock_weekly MODIFY COLUMN open COMMENT '开盘价(KLINE f52,元)';
ALTER TABLE stock_weekly MODIFY COLUMN high COMMENT '最高价(KLINE f54,元)';
ALTER TABLE stock_weekly MODIFY COLUMN low COMMENT '最低价(KLINE f55,元)';
ALTER TABLE stock_weekly MODIFY COLUMN close COMMENT '收盘价(KLINE f53,元)';
ALTER TABLE stock_weekly MODIFY COLUMN vol COMMENT '成交量(KLINE f56,手)';
ALTER TABLE stock_weekly MODIFY COLUMN amount COMMENT '成交额(KLINE f57,元)';
ALTER TABLE stock_weekly MODIFY COLUMN chg_amount COMMENT '涨跌额(KLINE f60,元)';
ALTER TABLE stock_weekly MODIFY COLUMN amplitude COMMENT '振幅(KLINE f58,%)';
ALTER TABLE stock_weekly MODIFY COLUMN volume_ratio COMMENT '量比(f10)';
ALTER TABLE stock_weekly MODIFY COLUMN avg_price COMMENT '均价(f11,元)';
ALTER TABLE stock_weekly MODIFY COLUMN main_net COMMENT '主力净流入(f62,元)';
ALTER TABLE stock_weekly MODIFY COLUMN pe_static COMMENT '静态市盈率(f115)';
ALTER TABLE stock_weekly MODIFY COLUMN leader_code COMMENT '领涨股代码(f128)';
ALTER TABLE stock_weekly MODIFY COLUMN industry_code COMMENT '行业代码(f140)';
ALTER TABLE stock_weekly MODIFY COLUMN concept_code COMMENT '概念代码(f141)';
ALTER TABLE stock_weekly MODIFY COLUMN market_code COMMENT '市场码(f152): 0=深 1=沪 2=京';
ALTER TABLE stock_weekly MODIFY COLUMN data_source COMMENT '数据来源: 0=东财 1=同花顺';
ALTER TABLE stock_weekly MODIFY COLUMN src_detail COMMENT '来源URL/接口/备注';
ALTER TABLE stock_weekly MODIFY COLUMN create_date COMMENT '入库日期';
ALTER TABLE stock_weekly MODIFY COLUMN update_date COMMENT '更新时间';

-- ---------------------------- index_daily(指数日线) ----------------------------
ALTER TABLE index_daily MODIFY COLUMN trade_date COMMENT '交易日期';
ALTER TABLE index_daily MODIFY COLUMN index_code COMMENT '指数代码(如 000001.SH)';
ALTER TABLE index_daily MODIFY COLUMN index_name COMMENT '指数名称';
ALTER TABLE index_daily MODIFY COLUMN open COMMENT '开盘价(f17,元)';
ALTER TABLE index_daily MODIFY COLUMN high COMMENT '最高价(f15,元)';
ALTER TABLE index_daily MODIFY COLUMN low COMMENT '最低价(f16,元)';
ALTER TABLE index_daily MODIFY COLUMN close COMMENT '收盘价(f2,元)';
ALTER TABLE index_daily MODIFY COLUMN pre_close COMMENT '昨收价(f18,元)';
ALTER TABLE index_daily MODIFY COLUMN pct_chg COMMENT '涨跌幅(自算:(close-pre_close)/pre_close*100,%)';
ALTER TABLE index_daily MODIFY COLUMN vol COMMENT '成交量(f5,股)';
ALTER TABLE index_daily MODIFY COLUMN amount COMMENT '成交额(f6,元)';
ALTER TABLE index_daily MODIFY COLUMN turnover COMMENT '换手率(%,指数一般无,可能为NULL)';
ALTER TABLE index_daily MODIFY COLUMN data_source COMMENT '数据来源: 0=东财 1=同花顺';
ALTER TABLE index_daily MODIFY COLUMN src_detail COMMENT '来源URL/接口/备注';
ALTER TABLE index_daily MODIFY COLUMN create_date COMMENT '入库日期';
ALTER TABLE index_daily MODIFY COLUMN update_date COMMENT '更新时间';

-- ---------------------------- stock_kline_minute(个股分钟K线) ----------------------------
ALTER TABLE stock_kline_minute MODIFY COLUMN trade_date COMMENT '交易日期';
ALTER TABLE stock_kline_minute MODIFY COLUMN ts_code COMMENT '股票代码';
ALTER TABLE stock_kline_minute MODIFY COLUMN stock_name COMMENT '股票名称';
ALTER TABLE stock_kline_minute MODIFY COLUMN minute_time COMMENT '分钟时间(如 2026-08-07 09:31:00)';
ALTER TABLE stock_kline_minute MODIFY COLUMN open COMMENT '开盘价(KLINE f52,元)';
ALTER TABLE stock_kline_minute MODIFY COLUMN high COMMENT '最高价(KLINE f54,元)';
ALTER TABLE stock_kline_minute MODIFY COLUMN low COMMENT '最低价(KLINE f55,元)';
ALTER TABLE stock_kline_minute MODIFY COLUMN close COMMENT '收盘价(KLINE f53,元)';
ALTER TABLE stock_kline_minute MODIFY COLUMN vol COMMENT '成交量(KLINE f56,手)';
ALTER TABLE stock_kline_minute MODIFY COLUMN amount COMMENT '成交额(KLINE f57,元)';
ALTER TABLE stock_kline_minute MODIFY COLUMN amplitude COMMENT '振幅(KLINE f58,%)';
ALTER TABLE stock_kline_minute MODIFY COLUMN pct_chg COMMENT '涨跌幅(KLINE f59,%)';
ALTER TABLE stock_kline_minute MODIFY COLUMN turnover COMMENT '换手率(KLINE f61,%)';
ALTER TABLE stock_kline_minute MODIFY COLUMN data_source COMMENT '数据来源: 1=东财';
ALTER TABLE stock_kline_minute MODIFY COLUMN create_date COMMENT '入库日期';

-- ---------------------------- board_daily(板块日线) ----------------------------
ALTER TABLE board_daily MODIFY COLUMN trade_date COMMENT '交易日期';
ALTER TABLE board_daily MODIFY COLUMN board_code COMMENT '板块代号(如 BK0450)';
ALTER TABLE board_daily MODIFY COLUMN board_name COMMENT '板块名称';
ALTER TABLE board_daily MODIFY COLUMN board_type COMMENT '板块类型: 1=地域 2=行业 3=概念';
ALTER TABLE board_daily MODIFY COLUMN pct_chg COMMENT '涨跌幅(f3,%)';
ALTER TABLE board_daily MODIFY COLUMN amount COMMENT '成交额(f6,元)';
ALTER TABLE board_daily MODIFY COLUMN up_count COMMENT '板块上涨家数(f104)';
ALTER TABLE board_daily MODIFY COLUMN down_count COMMENT '板块下跌家数(f105)';
ALTER TABLE board_daily MODIFY COLUMN limit_up_count COMMENT '板块内涨停家数(预留字段,暂无数据)';
ALTER TABLE board_daily MODIFY COLUMN leading_code COMMENT '领涨股代码(f140)';
ALTER TABLE board_daily MODIFY COLUMN leading_name COMMENT '领涨股名称(f128)';
ALTER TABLE board_daily MODIFY COLUMN main_net COMMENT '主力净流入(f62,元)';
ALTER TABLE board_daily MODIFY COLUMN board_code2 COMMENT 'f141(预留字段,暂无数据)';
ALTER TABLE board_daily MODIFY COLUMN price COMMENT '板块指数价格/收盘价(f2,元)';
ALTER TABLE board_daily MODIFY COLUMN rise_fall COMMENT '板块指数涨跌额(f4,元)';
ALTER TABLE board_daily MODIFY COLUMN volume COMMENT '成交量(f5,手)';
ALTER TABLE board_daily MODIFY COLUMN amplitude COMMENT '振幅(f7,%)';
ALTER TABLE board_daily MODIFY COLUMN high_price COMMENT '板块指数最高价(f15,元)';
ALTER TABLE board_daily MODIFY COLUMN low_price COMMENT '板块指数最低价(f16,元)';
ALTER TABLE board_daily MODIFY COLUMN today_open_price COMMENT '板块指数今开(f17,元)';
ALTER TABLE board_daily MODIFY COLUMN yesterday_received_price COMMENT '板块指数昨收(f18,元)';
ALTER TABLE board_daily MODIFY COLUMN volume_ratio COMMENT '板块量比(f10)';
ALTER TABLE board_daily MODIFY COLUMN turnover_ratio COMMENT '板块换手率(f8,%)';
ALTER TABLE board_daily MODIFY COLUMN total_market_value COMMENT '板块总市值(f20,元)';
ALTER TABLE board_daily MODIFY COLUMN circulation_market_value COMMENT '板块流通市值(f21,元)';
ALTER TABLE board_daily MODIFY COLUMN data_source COMMENT '数据来源: 0=东财 1=同花顺';
ALTER TABLE board_daily MODIFY COLUMN src_detail COMMENT '来源URL/接口/备注';
ALTER TABLE board_daily MODIFY COLUMN create_date COMMENT '入库日期';
ALTER TABLE board_daily MODIFY COLUMN update_date COMMENT '更新时间';

-- ========== 三、池子表(涨跌停/炸板/强势/次新) ==========

-- ---------------------------- limit_up_pool(涨停池) ----------------------------
ALTER TABLE limit_up_pool MODIFY COLUMN trade_date COMMENT '交易日期';
ALTER TABLE limit_up_pool MODIFY COLUMN ts_code COMMENT '股票代码(带后缀)';
ALTER TABLE limit_up_pool MODIFY COLUMN stock_name COMMENT '股票名称';
ALTER TABLE limit_up_pool MODIFY COLUMN latest_price COMMENT '最新价(p/100,元)';
ALTER TABLE limit_up_pool MODIFY COLUMN pct_chg COMMENT '涨跌幅(zdp,%)';
ALTER TABLE limit_up_pool MODIFY COLUMN board_pos COMMENT '连板数(lbc,第几板)';
ALTER TABLE limit_up_pool MODIFY COLUMN is_first COMMENT '是否首板: 1=首板(lbc==1)';
ALTER TABLE limit_up_pool MODIFY COLUMN is_continuous COMMENT '是否连板: 1=连板(lbc≥2)';
ALTER TABLE limit_up_pool MODIFY COLUMN limit_style COMMENT '封板形态: 一字/换手';
ALTER TABLE limit_up_pool MODIFY COLUMN open_time COMMENT '首次封板时间(fbt,HH:mm:ss)';
ALTER TABLE limit_up_pool MODIFY COLUMN last_time COMMENT '最后封板时间(lbt,HH:mm:ss)';
ALTER TABLE limit_up_pool MODIFY COLUMN open_times COMMENT '开板次数(zbc)';
ALTER TABLE limit_up_pool MODIFY COLUMN fund COMMENT '封单资金(fund,元)';
ALTER TABLE limit_up_pool MODIFY COLUMN amount COMMENT '成交额(amount,元)';
ALTER TABLE limit_up_pool MODIFY COLUMN ltsz COMMENT '流通市值(ltsz,元)';
ALTER TABLE limit_up_pool MODIFY COLUMN tshare COMMENT '总市值(tshare,元)';
ALTER TABLE limit_up_pool MODIFY COLUMN turnover_rate COMMENT '换手率(hs,%)';
ALTER TABLE limit_up_pool MODIFY COLUMN board_code COMMENT '所属行业板块代码(hybk,BKxxxx)';
ALTER TABLE limit_up_pool MODIFY COLUMN zttj_ct COMMENT '涨停统计-连板数(zttj.ct)';
ALTER TABLE limit_up_pool MODIFY COLUMN zttj_days COMMENT '涨停统计-天数(zttj.days)';
ALTER TABLE limit_up_pool MODIFY COLUMN data_source COMMENT '数据来源: 0=东财 1=同花顺';
ALTER TABLE limit_up_pool MODIFY COLUMN src_detail COMMENT '来源URL/接口/备注';
ALTER TABLE limit_up_pool MODIFY COLUMN create_date COMMENT '入库日期';
ALTER TABLE limit_up_pool MODIFY COLUMN update_date COMMENT '更新时间';

-- ---------------------------- limit_down_pool(跌停池) ----------------------------
ALTER TABLE limit_down_pool MODIFY COLUMN trade_date COMMENT '交易日期';
ALTER TABLE limit_down_pool MODIFY COLUMN ts_code COMMENT '股票代码(带后缀)';
ALTER TABLE limit_down_pool MODIFY COLUMN stock_name COMMENT '股票名称';
ALTER TABLE limit_down_pool MODIFY COLUMN latest_price COMMENT '最新价(p/100,元)';
ALTER TABLE limit_down_pool MODIFY COLUMN pct_chg COMMENT '涨跌幅(zdp,%)';
ALTER TABLE limit_down_pool MODIFY COLUMN pe COMMENT '市盈率TTM(f9,预留字段,暂无数据)';
ALTER TABLE limit_down_pool MODIFY COLUMN fund COMMENT '跌停封单资金(fund,元)';
ALTER TABLE limit_down_pool MODIFY COLUMN last_time COMMENT '最后封板时间(lbt,HH:mm:ss)';
ALTER TABLE limit_down_pool MODIFY COLUMN fba COMMENT '板上成交额(预留字段,暂无数据)';
ALTER TABLE limit_down_pool MODIFY COLUMN days COMMENT '连续跌停天数(预留字段,暂无数据)';
ALTER TABLE limit_down_pool MODIFY COLUMN oc COMMENT '开板次数(预留字段,暂无数据)';
ALTER TABLE limit_down_pool MODIFY COLUMN amount COMMENT '成交额(amount,元)';
ALTER TABLE limit_down_pool MODIFY COLUMN ltsz COMMENT '流通市值(ltsz,元)';
ALTER TABLE limit_down_pool MODIFY COLUMN tshare COMMENT '总市值(tshare,元)';
ALTER TABLE limit_down_pool MODIFY COLUMN turnover_rate COMMENT '换手率(hs,%)';
ALTER TABLE limit_down_pool MODIFY COLUMN board_code COMMENT '所属行业板块代码(hybk,BKxxxx)';
ALTER TABLE limit_down_pool MODIFY COLUMN data_source COMMENT '数据来源: 0=东财 1=同花顺';
ALTER TABLE limit_down_pool MODIFY COLUMN src_detail COMMENT '来源URL/接口/备注';
ALTER TABLE limit_down_pool MODIFY COLUMN create_date COMMENT '入库日期';
ALTER TABLE limit_down_pool MODIFY COLUMN update_date COMMENT '更新时间';

-- ---------------------------- zhaban_pool(炸板池) ----------------------------
ALTER TABLE zhaban_pool MODIFY COLUMN trade_date COMMENT '交易日期';
ALTER TABLE zhaban_pool MODIFY COLUMN ts_code COMMENT '股票代码(带后缀)';
ALTER TABLE zhaban_pool MODIFY COLUMN stock_name COMMENT '股票名称';
ALTER TABLE zhaban_pool MODIFY COLUMN latest_price COMMENT '最新价(p/100,元)';
ALTER TABLE zhaban_pool MODIFY COLUMN pct_chg COMMENT '涨跌幅(zdp,%)';
ALTER TABLE zhaban_pool MODIFY COLUMN ztp COMMENT '涨停价(ztp/100,≥1e9置空,元)';
ALTER TABLE zhaban_pool MODIFY COLUMN zf COMMENT '振幅(zf,%)';
ALTER TABLE zhaban_pool MODIFY COLUMN zs COMMENT '涨速(zs,%)';
ALTER TABLE zhaban_pool MODIFY COLUMN open_time COMMENT '首次封板时间(fbt,HH:mm:ss)';
ALTER TABLE zhaban_pool MODIFY COLUMN open_times COMMENT '炸板次数(zbc)';
ALTER TABLE zhaban_pool MODIFY COLUMN amount COMMENT '成交额(amount,元)';
ALTER TABLE zhaban_pool MODIFY COLUMN ltsz COMMENT '流通市值(ltsz,元)';
ALTER TABLE zhaban_pool MODIFY COLUMN tshare COMMENT '总市值(tshare,元)';
ALTER TABLE zhaban_pool MODIFY COLUMN turnover_rate COMMENT '换手率(hs,%)';
ALTER TABLE zhaban_pool MODIFY COLUMN board_code COMMENT '所属行业板块代码(hybk,BKxxxx)';
ALTER TABLE zhaban_pool MODIFY COLUMN zttj_ct COMMENT '涨停统计-连板数(zttj.ct)';
ALTER TABLE zhaban_pool MODIFY COLUMN zttj_days COMMENT '涨停统计-天数(zttj.days)';
ALTER TABLE zhaban_pool MODIFY COLUMN data_source COMMENT '数据来源: 0=东财 1=同花顺';
ALTER TABLE zhaban_pool MODIFY COLUMN src_detail COMMENT '来源URL/接口/备注';
ALTER TABLE zhaban_pool MODIFY COLUMN create_date COMMENT '入库日期';
ALTER TABLE zhaban_pool MODIFY COLUMN update_date COMMENT '更新时间';

-- ---------------------------- strong_pool(强势池) ----------------------------
ALTER TABLE strong_pool MODIFY COLUMN trade_date COMMENT '交易日期';
ALTER TABLE strong_pool MODIFY COLUMN ts_code COMMENT '股票代码(带后缀)';
ALTER TABLE strong_pool MODIFY COLUMN stock_name COMMENT '股票名称';
ALTER TABLE strong_pool MODIFY COLUMN latest_price COMMENT '最新价(p/100,元)';
ALTER TABLE strong_pool MODIFY COLUMN pct_chg COMMENT '涨跌幅(zdp,%)';
ALTER TABLE strong_pool MODIFY COLUMN ztp COMMENT '涨停价(ztp/100,≥1e9置空,元)';
ALTER TABLE strong_pool MODIFY COLUMN zs COMMENT '涨速(zs,%)';
ALTER TABLE strong_pool MODIFY COLUMN nh COMMENT '是否N日新高(nh,1=是)';
ALTER TABLE strong_pool MODIFY COLUMN board_pos COMMENT '连板数(lbc)';
ALTER TABLE strong_pool MODIFY COLUMN lb COMMENT '量比(lb)';
ALTER TABLE strong_pool MODIFY COLUMN amount COMMENT '成交额(amount,元)';
ALTER TABLE strong_pool MODIFY COLUMN ltsz COMMENT '流通市值(ltsz,元)';
ALTER TABLE strong_pool MODIFY COLUMN tshare COMMENT '总市值(tshare,元)';
ALTER TABLE strong_pool MODIFY COLUMN turnover_rate COMMENT '换手率(hs,%)';
ALTER TABLE strong_pool MODIFY COLUMN board_code COMMENT '所属行业板块代码(hybk,BKxxxx)';
ALTER TABLE strong_pool MODIFY COLUMN zttj_ct COMMENT '涨停统计-连板数(zttj.ct)';
ALTER TABLE strong_pool MODIFY COLUMN zttj_days COMMENT '涨停统计-天数(zttj.days)';
ALTER TABLE strong_pool MODIFY COLUMN data_source COMMENT '数据来源: 0=东财 1=同花顺';
ALTER TABLE strong_pool MODIFY COLUMN src_detail COMMENT '来源URL/接口/备注';
ALTER TABLE strong_pool MODIFY COLUMN create_date COMMENT '入库日期';
ALTER TABLE strong_pool MODIFY COLUMN update_date COMMENT '更新时间';

-- ---------------------------- cixin_pool(次新池) ----------------------------
ALTER TABLE cixin_pool MODIFY COLUMN trade_date COMMENT '交易日期';
ALTER TABLE cixin_pool MODIFY COLUMN ts_code COMMENT '股票代码(带后缀)';
ALTER TABLE cixin_pool MODIFY COLUMN stock_name COMMENT '股票名称';
ALTER TABLE cixin_pool MODIFY COLUMN latest_price COMMENT '最新价(p/100,元)';
ALTER TABLE cixin_pool MODIFY COLUMN pct_chg COMMENT '涨跌幅(zdp,%)';
ALTER TABLE cixin_pool MODIFY COLUMN ztp COMMENT '涨停价(ztp/100,≥1e9置空,元)';
ALTER TABLE cixin_pool MODIFY COLUMN ods COMMENT '开板几日(ods)';
ALTER TABLE cixin_pool MODIFY COLUMN od COMMENT '开板日期(od,YYYYMMDD)';
ALTER TABLE cixin_pool MODIFY COLUMN ipod COMMENT '上市日期(ipod,YYYYMMDD)';
ALTER TABLE cixin_pool MODIFY COLUMN o COMMENT '是否新高(o,1=是)';
ALTER TABLE cixin_pool MODIFY COLUMN nh COMMENT '新高备用字段(nh)';
ALTER TABLE cixin_pool MODIFY COLUMN amount COMMENT '成交额(amount,元)';
ALTER TABLE cixin_pool MODIFY COLUMN ltsz COMMENT '流通市值(ltsz,元)';
ALTER TABLE cixin_pool MODIFY COLUMN tshare COMMENT '总市值(tshare,元)';
ALTER TABLE cixin_pool MODIFY COLUMN turnover_rate COMMENT '换手率(hs,%)';
ALTER TABLE cixin_pool MODIFY COLUMN board_code COMMENT '所属行业板块代码(hybk,BKxxxx)';
ALTER TABLE cixin_pool MODIFY COLUMN zttj_ct COMMENT '涨停统计-连板数(zttj.ct)';
ALTER TABLE cixin_pool MODIFY COLUMN zttj_days COMMENT '涨停统计-天数(zttj.days)';
ALTER TABLE cixin_pool MODIFY COLUMN data_source COMMENT '数据来源: 0=东财 1=同花顺';
ALTER TABLE cixin_pool MODIFY COLUMN src_detail COMMENT '来源URL/接口/备注';
ALTER TABLE cixin_pool MODIFY COLUMN create_date COMMENT '入库日期';
ALTER TABLE cixin_pool MODIFY COLUMN update_date COMMENT '更新时间';
