-- ============================================================================
-- 数据库字段注释（OpenGauss/PostgreSQL）
-- 运行：psql -h 100.92.86.64 -p 15432 -U dbuser -d postgres -f schema-comments.sql
-- ============================================================================

-- ============================================================================
-- 一、board_daily（板块日线）
-- ============================================================================
COMMENT ON COLUMN board_daily.trade_date IS '交易日期';
COMMENT ON COLUMN board_daily.board_code IS '板块代号';
COMMENT ON COLUMN board_daily.board_name IS '板块名称';
COMMENT ON COLUMN board_daily.pct_chg IS '涨跌幅%';
COMMENT ON COLUMN board_daily.amount IS '成交额(元)';
COMMENT ON COLUMN board_daily.up_count IS '上涨家数';
COMMENT ON COLUMN board_daily.down_count IS '下跌家数';
COMMENT ON COLUMN board_daily.limit_up_count IS '板块内涨停家数';
COMMENT ON COLUMN board_daily.leading_code IS '领涨股代码';
COMMENT ON COLUMN board_daily.leading_name IS '领涨股名称';
COMMENT ON COLUMN board_daily.data_source IS '来源：0=东财 1=同花顺';
COMMENT ON COLUMN board_daily.src_detail IS '来源URL/接口/备注';
COMMENT ON COLUMN board_daily.f62_main_net IS 'f62 主力净流入(元)';
COMMENT ON COLUMN board_daily.f107_unknown IS 'f107 待确认';
COMMENT ON COLUMN board_daily.f124_timestamp IS 'f124 数据更新时间戳';
COMMENT ON COLUMN board_daily.f140_board_code IS 'f140 行业代码';
COMMENT ON COLUMN board_daily.f141_board_code2 IS 'f141 概念代码';
COMMENT ON COLUMN board_daily.f207_unknown IS 'f207 待确认';
COMMENT ON COLUMN board_daily.f208_unknown IS 'f208 待确认';
COMMENT ON COLUMN board_daily.f209_unknown IS 'f209 待确认';
COMMENT ON COLUMN board_daily.f222_unknown IS 'f222 待确认';
COMMENT ON COLUMN board_daily.create_date IS '创建日期';
COMMENT ON COLUMN board_daily.board_type IS '板块类型：1地域 2行业 3概念';
COMMENT ON COLUMN board_daily.main_net IS '主力净流入(元)';
COMMENT ON COLUMN board_daily.board_code2 IS '行业代码';
COMMENT ON COLUMN board_daily.update_date IS '修改日期';

-- ============================================================================
-- 二、stock_daily（个股日线）
-- ============================================================================
COMMENT ON COLUMN stock_daily.trade_date IS '交易日期';
COMMENT ON COLUMN stock_daily.ts_code IS '股票代码(如 600000.SH)';
COMMENT ON COLUMN stock_daily.stock_name IS '股票名称';
COMMENT ON COLUMN stock_daily.open IS '开盘价';
COMMENT ON COLUMN stock_daily.high IS '最高价';
COMMENT ON COLUMN stock_daily.low IS '最低价';
COMMENT ON COLUMN stock_daily.close IS '收盘价';
COMMENT ON COLUMN stock_daily.pre_close IS '昨收';
COMMENT ON COLUMN stock_daily.pct_chg IS '涨跌幅%';
COMMENT ON COLUMN stock_daily.vol IS '成交量(手)';
COMMENT ON COLUMN stock_daily.amount IS '成交额(元)';
COMMENT ON COLUMN stock_daily.turnover IS '换手率%';
COMMENT ON COLUMN stock_daily.total_mv IS '总市值(元)';
COMMENT ON COLUMN stock_daily.circ_mv IS '流通市值(元)';
COMMENT ON COLUMN stock_daily.pe IS '市盈率(TTM)';
COMMENT ON COLUMN stock_daily.is_limit_up IS '是否涨停 1/0';
COMMENT ON COLUMN stock_daily.is_limit_down IS '是否跌停 1/0';
COMMENT ON COLUMN stock_daily.data_source IS '来源：0=东财 1=同花顺';
COMMENT ON COLUMN stock_daily.src_detail IS '来源URL/接口/备注';
COMMENT ON COLUMN stock_daily.chg_amount IS 'f4 涨跌额';
COMMENT ON COLUMN stock_daily.amplitude IS 'f7 振幅%';
COMMENT ON COLUMN stock_daily.volume_ratio IS 'f10 量比';
COMMENT ON COLUMN stock_daily.avg_price IS 'f11 均价';
COMMENT ON COLUMN stock_daily.main_net IS 'f62 主力净流入(元)';
COMMENT ON COLUMN stock_daily.pe_static IS 'f115 静态市盈率';
COMMENT ON COLUMN stock_daily.leader_code IS 'f128 领涨股代码';
COMMENT ON COLUMN stock_daily.industry_code IS 'f140 所属行业代码';
COMMENT ON COLUMN stock_daily.concept_code IS 'f141 所属概念代码';
COMMENT ON COLUMN stock_daily.market_code IS 'f152 市场码(0深/1沪/2京)';
COMMENT ON COLUMN stock_daily.reserved_f24 IS 'f24 待确认';
COMMENT ON COLUMN stock_daily.reserved_f25 IS 'f25 待确认';
COMMENT ON COLUMN stock_daily.reserved_f107 IS 'f107 待确认';
COMMENT ON COLUMN stock_daily.reserved_f136 IS 'f136 待确认';
COMMENT ON COLUMN stock_daily.reserved_f173 IS 'f173 待确认';
COMMENT ON COLUMN stock_daily.create_date IS '创建日期';
COMMENT ON COLUMN stock_daily.update_date IS '修改日期';

-- ============================================================================
-- 三、stock_weekly（个股周线）
-- ============================================================================
COMMENT ON COLUMN stock_weekly.trade_date IS '交易日期(周末)';
COMMENT ON COLUMN stock_weekly.ts_code IS '股票代码';
COMMENT ON COLUMN stock_weekly.open IS '开盘价';
COMMENT ON COLUMN stock_weekly.high IS '最高价';
COMMENT ON COLUMN stock_weekly.low IS '最低价';
COMMENT ON COLUMN stock_weekly.close IS '收盘价';
COMMENT ON COLUMN stock_weekly.vol IS '成交量(手)';
COMMENT ON COLUMN stock_weekly.amount IS '成交额(元)';
COMMENT ON COLUMN stock_weekly.data_source IS '来源：0=东财 1=同花顺';
COMMENT ON COLUMN stock_weekly.src_detail IS '来源URL/接口/备注';
COMMENT ON COLUMN stock_weekly.chg_amount IS 'f4 涨跌额';
COMMENT ON COLUMN stock_weekly.amplitude IS 'f7 振幅%';
COMMENT ON COLUMN stock_weekly.volume_ratio IS 'f10 量比';
COMMENT ON COLUMN stock_weekly.avg_price IS 'f11 均价';
COMMENT ON COLUMN stock_weekly.main_net IS 'f62 主力净流入(元)';
COMMENT ON COLUMN stock_weekly.pe_static IS 'f115 静态市盈率';
COMMENT ON COLUMN stock_weekly.leader_code IS 'f128 领涨股代码';
COMMENT ON COLUMN stock_weekly.industry_code IS 'f140 行业代码';
COMMENT ON COLUMN stock_weekly.concept_code IS 'f141 概念代码';
COMMENT ON COLUMN stock_weekly.market_code IS 'f152 市场码';
COMMENT ON COLUMN stock_weekly.create_date IS '创建日期';
COMMENT ON COLUMN stock_weekly.update_date IS '修改日期';

-- ============================================================================
-- 四、index_daily（指数日线）
-- ============================================================================
COMMENT ON COLUMN index_daily.trade_date IS '交易日期';
COMMENT ON COLUMN index_daily.index_code IS '指数代码(如 000001.SH)';
COMMENT ON COLUMN index_daily.index_name IS '指数名称';
COMMENT ON COLUMN index_daily.open IS '开盘价';
COMMENT ON COLUMN index_daily.high IS '最高价';
COMMENT ON COLUMN index_daily.low IS '最低价';
COMMENT ON COLUMN index_daily.close IS '收盘价';
COMMENT ON COLUMN index_daily.pre_close IS '昨收';
COMMENT ON COLUMN index_daily.pct_chg IS '涨跌幅%';
COMMENT ON COLUMN index_daily.vol IS '成交量(手)';
COMMENT ON COLUMN index_daily.amount IS '成交额(元)';
COMMENT ON COLUMN index_daily.turnover IS '换手率%';
COMMENT ON COLUMN index_daily.data_source IS '来源：0=东财 1=同花顺';
COMMENT ON COLUMN index_daily.src_detail IS '来源URL/接口/备注';
COMMENT ON COLUMN index_daily.create_date IS '创建日期';
COMMENT ON COLUMN index_daily.update_date IS '修改日期';

-- ============================================================================
-- 五、limit_pool（涨跌停/炸板池）
-- ============================================================================
COMMENT ON COLUMN limit_pool.trade_date IS '交易日期';
COMMENT ON COLUMN limit_pool.ts_code IS '股票代码';
COMMENT ON COLUMN limit_pool.stock_name IS '股票名称';
COMMENT ON COLUMN limit_pool.limit_type IS 'limit_up涨停 / limit_down跌停 / zhaban炸板';
COMMENT ON COLUMN limit_pool.board_pos IS '连板数(板位)';
COMMENT ON COLUMN limit_pool.is_first IS '是否首板';
COMMENT ON COLUMN limit_pool.is_continuous IS '是否连板(>=2)';
COMMENT ON COLUMN limit_pool.limit_style IS '一字 / T字 / 换手 / 自然 / 烂板';
COMMENT ON COLUMN limit_pool.open_time IS '首次封板时间';
COMMENT ON COLUMN limit_pool.last_time IS '最后封板/炸板时间';
COMMENT ON COLUMN limit_pool.open_times IS '开板次数';
COMMENT ON COLUMN limit_pool.bid_amount IS '涨停封单金额(元)';
COMMENT ON COLUMN limit_pool.turnover IS '换手率%';
COMMENT ON COLUMN limit_pool.pct_chg IS '涨跌幅%';
COMMENT ON COLUMN limit_pool.reason IS '涨停原因/题材标签';
COMMENT ON COLUMN limit_pool.board_code IS '所属板块(hybk)';
COMMENT ON COLUMN limit_pool.board_name IS '板块名称(实测无hymc)';
COMMENT ON COLUMN limit_pool.data_source IS '来源：0=东财 1=同花顺';
COMMENT ON COLUMN limit_pool.src_detail IS '来源URL/接口/备注';
COMMENT ON COLUMN limit_pool.amount IS '成交额(元)';
COMMENT ON COLUMN limit_pool.fund IS '封单资金(涨停池)';
COMMENT ON COLUMN limit_pool.ltsz IS '流通市值(元)';
COMMENT ON COLUMN limit_pool.tshare IS '总股本(元)';
COMMENT ON COLUMN limit_pool.zf IS '涨幅%(炸板池)';
COMMENT ON COLUMN limit_pool.zs IS '振幅%(炸板池)';
COMMENT ON COLUMN limit_pool.ztp IS '涨停价';
COMMENT ON COLUMN limit_pool.zttj_ct IS '连板统计-连板数';
COMMENT ON COLUMN limit_pool.zttj_days IS '连板统计-天数';
COMMENT ON COLUMN limit_pool.lb IS '连板数(强势池)';
COMMENT ON COLUMN limit_pool.nh IS 'N日新高';
COMMENT ON COLUMN limit_pool.ztf IS '涨停封单描述';
COMMENT ON COLUMN limit_pool.ipod IS '上市日期(次新池)';
COMMENT ON COLUMN limit_pool.o IS '开盘价(次新池)';
COMMENT ON COLUMN limit_pool.od IS '上市天数';
COMMENT ON COLUMN limit_pool.ods IS '上市天数';
COMMENT ON COLUMN limit_pool.create_date IS '创建日期';
COMMENT ON COLUMN limit_pool.update_date IS '修改日期';

-- ============================================================================
-- 六、strong_pool（强势股池）
-- ============================================================================
COMMENT ON COLUMN strong_pool.trade_date IS '交易日期';
COMMENT ON COLUMN strong_pool.ts_code IS '股票代码';
COMMENT ON COLUMN strong_pool.stock_name IS '股票名称';
COMMENT ON COLUMN strong_pool.strong_type IS '涨幅 / 新高 / 多头(多头排列)';
COMMENT ON COLUMN strong_pool.change_pct IS '当日涨幅%';
COMMENT ON COLUMN strong_pool.high_days IS '创N日新高(新高天数)';
COMMENT ON COLUMN strong_pool.ma_status IS '多头排列描述';
COMMENT ON COLUMN strong_pool.data_source IS '来源：0=东财 1=同花顺';
COMMENT ON COLUMN strong_pool.src_detail IS '来源URL/接口/备注';
COMMENT ON COLUMN strong_pool.amount IS '成交额(元)';
COMMENT ON COLUMN strong_pool.pct_chg IS '涨跌幅%';
COMMENT ON COLUMN strong_pool.ltsz IS '流通市值(元)';
COMMENT ON COLUMN strong_pool.tshare IS '总股本(元)';
COMMENT ON COLUMN strong_pool.zs IS '振幅%';
COMMENT ON COLUMN strong_pool.ztp IS '涨停价';
COMMENT ON COLUMN strong_pool.lb IS '连板数';
COMMENT ON COLUMN strong_pool.nh IS 'N日新高';
COMMENT ON COLUMN strong_pool.ztf IS '涨停封单描述';
COMMENT ON COLUMN strong_pool.zttj_ct IS '连板统计-连板数';
COMMENT ON COLUMN strong_pool.zttj_days IS '连板统计-天数';
COMMENT ON COLUMN strong_pool.board_code IS '板块代码';
COMMENT ON COLUMN strong_pool.create_date IS '创建日期';
COMMENT ON COLUMN strong_pool.update_date IS '修改日期';

-- ============================================================================
-- 七、board_daily（板块日线）- 已在上面定义，此处省略
-- ============================================================================

-- ============================================================================
-- 八、stock_board_rel（股票-板块关联）
-- ============================================================================
COMMENT ON COLUMN stock_board_rel.ts_code IS '股票代码';
COMMENT ON COLUMN stock_board_rel.board_code IS '板块代号';
COMMENT ON COLUMN stock_board_rel.board_name IS '板块名称';
COMMENT ON COLUMN stock_board_rel.board_type IS '板块类型：1地域 2行业 3概念';
COMMENT ON COLUMN stock_board_rel.is_leader IS '是否板块龙头';
COMMENT ON COLUMN stock_board_rel.is_midarm IS '是否中军';
COMMENT ON COLUMN stock_board_rel.weight IS '权重';
COMMENT ON COLUMN stock_board_rel.effective_date IS '生效日';
COMMENT ON COLUMN stock_board_rel.data_source IS '来源：0=东财 1=同花顺';
COMMENT ON COLUMN stock_board_rel.src_detail IS '来源URL/接口/备注';
COMMENT ON COLUMN stock_board_rel.create_date IS '创建日期';
COMMENT ON COLUMN stock_board_rel.update_date IS '修改日期';

-- ============================================================================
-- 九、dragon_tiger（龙虎榜）
-- ============================================================================
COMMENT ON COLUMN dragon_tiger.trade_date IS '交易日期';
COMMENT ON COLUMN dragon_tiger.ts_code IS '股票代码';
COMMENT ON COLUMN dragon_tiger.stock_name IS '股票名称';
COMMENT ON COLUMN dragon_tiger.reason IS '上榜原因';
COMMENT ON COLUMN dragon_tiger.explanation IS '上榜原因(详)';
COMMENT ON COLUMN dragon_tiger.abnormal_type IS '变动类型';
COMMENT ON COLUMN dragon_tiger.net_buy IS '龙虎榜净买额(元)';
COMMENT ON COLUMN dragon_tiger.total_buy IS '买入金额(元)';
COMMENT ON COLUMN dragon_tiger.total_sell IS '卖出金额(元)';
COMMENT ON COLUMN dragon_tiger.data_source IS '来源：0=东财 1=同花顺';
COMMENT ON COLUMN dragon_tiger.src_detail IS '来源URL/接口/备注';
COMMENT ON COLUMN dragon_tiger.billboard_deal_amt IS '龙虎榜成交额(元)';
COMMENT ON COLUMN dragon_tiger.accum_amount IS '累计成交额(元)';
COMMENT ON COLUMN dragon_tiger.buy_ratio IS '买入占比%';
COMMENT ON COLUMN dragon_tiger.sell_ratio IS '卖出占比%';
COMMENT ON COLUMN dragon_tiger.buy_seat IS '买出席位数';
COMMENT ON COLUMN dragon_tiger.sell_seat IS '卖出席位数';
COMMENT ON COLUMN dragon_tiger.change_rate IS '涨跌幅%';
COMMENT ON COLUMN dragon_tiger.close_price IS '收盘价';
COMMENT ON COLUMN dragon_tiger.turnoverrate IS '换手率%';
COMMENT ON COLUMN dragon_tiger.free_market_cap IS '流通市值(元)';
COMMENT ON COLUMN dragon_tiger.market IS '市场(SZ/BJ/SH)';
COMMENT ON COLUMN dragon_tiger.create_date IS '创建日期';
COMMENT ON COLUMN dragon_tiger.update_date IS '修改日期';

-- ============================================================================
-- 十、dt_detail（龙虎榜席位明细）
-- ============================================================================
COMMENT ON COLUMN dt_detail.trade_date IS '交易日期';
COMMENT ON COLUMN dt_detail.ts_code IS '股票代码';
COMMENT ON COLUMN dt_detail.seat_name IS '席位名称';
COMMENT ON COLUMN dt_detail.seat_type IS '机构 / 游资 / 深股通 / 沪股通 / 营业部';
COMMENT ON COLUMN dt_detail.buy IS '买入金额(元)';
COMMENT ON COLUMN dt_detail.sell IS '卖出金额(元)';
COMMENT ON COLUMN dt_detail.is_institution IS '是否机构';
COMMENT ON COLUMN dt_detail.is_famous IS '是否知名游资';
COMMENT ON COLUMN dt_detail.data_source IS '来源：0=东财 1=同花顺';
COMMENT ON COLUMN dt_detail.src_detail IS '来源URL/接口/备注';
COMMENT ON COLUMN dt_detail.create_date IS '创建日期';
COMMENT ON COLUMN dt_detail.update_date IS '修改日期';

-- ============================================================================
-- 十一、main_fund_flow（主力资金流）
-- ============================================================================
COMMENT ON COLUMN main_fund_flow.trade_date IS '交易日期';
COMMENT ON COLUMN main_fund_flow.obj_type IS 'stock / board / index';
COMMENT ON COLUMN main_fund_flow.ts_code IS '个股级代码';
COMMENT ON COLUMN main_fund_flow.board_code IS '板块级代码';
COMMENT ON COLUMN main_fund_flow.index_code IS '指数级代码';
COMMENT ON COLUMN main_fund_flow.main_net IS '主力净流入(元)';
COMMENT ON COLUMN main_fund_flow.super_big IS '超大单净流入(元)';
COMMENT ON COLUMN main_fund_flow.big_net IS '大单净流入(元)';
COMMENT ON COLUMN main_fund_flow.mid_net IS '中单净流入(元)';
COMMENT ON COLUMN main_fund_flow.small_net IS '小单净流入(元)';
COMMENT ON COLUMN main_fund_flow.data_source IS '来源：0=东财 1=同花顺';
COMMENT ON COLUMN main_fund_flow.src_detail IS '来源URL/接口/备注';
COMMENT ON COLUMN main_fund_flow.create_date IS '创建日期';
COMMENT ON COLUMN main_fund_flow.update_date IS '修改日期';

-- ============================================================================
-- 十二、northbound_flow（北向资金）
-- ============================================================================
COMMENT ON COLUMN northbound_flow.trade_date IS '交易日期';
COMMENT ON COLUMN northbound_flow.hk_hold_net IS '北向净买入(元)';
COMMENT ON COLUMN northbound_flow.sh_net IS '沪股通净买入(元)';
COMMENT ON COLUMN northbound_flow.sz_net IS '深股通净买入(元)';
COMMENT ON COLUMN northbound_flow.data_source IS '来源：0=东财 1=同花顺';
COMMENT ON COLUMN northbound_flow.src_detail IS '来源URL/接口/备注';
COMMENT ON COLUMN northbound_flow.create_date IS '创建日期';
COMMENT ON COLUMN northbound_flow.update_date IS '修改日期';

-- ============================================================================
-- 十三、news_event（新闻/政策/题材事件）
-- ============================================================================
COMMENT ON COLUMN news_event.event_id IS '事件ID';
COMMENT ON COLUMN news_event.event_time IS '事件时间';
COMMENT ON COLUMN news_event.title IS '标题';
COMMENT ON COLUMN news_event.content IS '内容';
COMMENT ON COLUMN news_event.source IS '来源';
COMMENT ON COLUMN news_event.category IS '政策 / 行业 / 公司 / 题材';
COMMENT ON COLUMN news_event.related_board IS '关联板块代码(逗号分隔)';
COMMENT ON COLUMN news_event.related_ts_code IS '关联个股代码(逗号分隔)';
COMMENT ON COLUMN news_event.sentiment_score IS '情感分 -1~1';
COMMENT ON COLUMN news_event.is_policy IS '是否政策';
COMMENT ON COLUMN news_event.create_date IS '创建日期';
COMMENT ON COLUMN news_event.update_date IS '修改日期';

-- ============================================================================
-- 十四、concept（题材静态属性）
-- ============================================================================
COMMENT ON COLUMN concept.theme_code IS '题材代码';
COMMENT ON COLUMN concept.theme_name IS '题材名称';
COMMENT ON COLUMN concept.theme_type IS '概念 / 行业 / 地域';
COMMENT ON COLUMN concept.scarcity IS '稀缺性 0~1';
COMMENT ON COLUMN concept.imagination IS '想象空间 0~1';
COMMENT ON COLUMN concept.data_source IS '来源：0=东财 1=同花顺';
COMMENT ON COLUMN concept.src_detail IS '来源URL/接口/备注';
COMMENT ON COLUMN concept.create_date IS '创建日期';
COMMENT ON COLUMN concept.update_date IS '修改日期';

-- ============================================================================
-- 十五、financial（财报）
-- ============================================================================
COMMENT ON COLUMN financial.ts_code IS '股票代码';
COMMENT ON COLUMN financial.end_date IS '报告期';
COMMENT ON COLUMN financial.report_type IS 'Q1 / Q2 / Q3 / 年报';
COMMENT ON COLUMN financial.ann_date IS '公告日期';
COMMENT ON COLUMN financial.revenue IS '营收(元)';
COMMENT ON COLUMN financial.net_profit IS '净利润(元)';
COMMENT ON COLUMN financial.net_profit_yoy IS '净利润同比%';
COMMENT ON COLUMN financial.roe IS '净资产收益率%';
COMMENT ON COLUMN financial.data_source IS '来源：0=东财 1=同花顺';
COMMENT ON COLUMN financial.src_detail IS '来源URL/接口/备注';
COMMENT ON COLUMN financial.create_date IS '创建日期';
COMMENT ON COLUMN financial.update_date IS '修改日期';

-- ============================================================================
-- 十六、trade_log（交易日志）
-- ============================================================================
COMMENT ON COLUMN trade_log.id IS 'ID';
COMMENT ON COLUMN trade_log.trade_date IS '交易日期';
COMMENT ON COLUMN trade_log.ts_code IS '股票代码';
COMMENT ON COLUMN trade_log.side IS 'buy / sell';
COMMENT ON COLUMN trade_log.price IS '价格';
COMMENT ON COLUMN trade_log.qty IS '数量';
COMMENT ON COLUMN trade_log.reason IS '买入逻辑';
COMMENT ON COLUMN trade_log.emotion_tag IS '执行心态标签';
COMMENT ON COLUMN trade_log.ying_dai IS '买对/买错/未明 三态处置';
COMMENT ON COLUMN trade_log.data_source IS '来源：99=用户手工';
COMMENT ON COLUMN trade_log.src_detail IS '来源URL/接口/备注';
COMMENT ON COLUMN trade_log.create_date IS '创建日期';
COMMENT ON COLUMN trade_log.update_date IS '修改日期';

-- ============================================================================
-- 十七、trade_calendar（交易日历）
-- ============================================================================
COMMENT ON COLUMN trade_calendar.trade_date IS '交易日期';
COMMENT ON COLUMN trade_calendar.is_trading IS '1=交易日 0=休市';
COMMENT ON COLUMN trade_calendar.data_source IS '来源：0=东财 1=同花顺';
COMMENT ON COLUMN trade_calendar.src_detail IS '来源URL/接口/备注';

-- ============================================================================
-- 十八、board_basic（板块基础数据）
-- ============================================================================
COMMENT ON COLUMN board_basic.id IS 'ID';
COMMENT ON COLUMN board_basic.board_type IS '板块类型：1地域 2行业 3概念';
COMMENT ON COLUMN board_basic.code IS '同花顺板块指数代码';
COMMENT ON COLUMN board_basic.board_code IS '板块代号(如 BK0450)';
COMMENT ON COLUMN board_basic.board_name IS '板块名称';
COMMENT ON COLUMN board_basic.features IS '备用字段';
COMMENT ON COLUMN board_basic.status IS '1=正常 0=删除';
COMMENT ON COLUMN board_basic.data_source IS '来源：0=东财 1=同花顺';
COMMENT ON COLUMN board_basic.create_date IS '创建日期';
COMMENT ON COLUMN board_basic.update_date IS '修改日期';
