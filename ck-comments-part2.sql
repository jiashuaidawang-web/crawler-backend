-- ============================================================================
-- ClickHouse 全库字段注释（crawler 数据库）第二部分
-- 衔接 ck-comments-part1.sql 之后运行
-- 运行: clickhouse-client --host <ck> --port=8123 -d crawler < ck-comments-part2.sql
-- ============================================================================

-- ========== 四、主题表(龙虎榜/资金流/北向) ==========

-- ---------------------------- dragon_tiger(龙虎榜主表) ----------------------------
ALTER TABLE dragon_tiger MODIFY COLUMN trade_date COMMENT '交易日期';
ALTER TABLE dragon_tiger MODIFY COLUMN ts_code COMMENT '股票代码(SECUCODE,带后缀)';
ALTER TABLE dragon_tiger MODIFY COLUMN stock_name COMMENT '股票名称(SECURITY_NAME_ABBR)';
ALTER TABLE dragon_tiger MODIFY COLUMN reason COMMENT '上榜原因(EXPLAIN)';
ALTER TABLE dragon_tiger MODIFY COLUMN explanation COMMENT '上榜原因详(EXPLANATION)';
ALTER TABLE dragon_tiger MODIFY COLUMN abnormal_type COMMENT '变动类型(CHANGE_TYPE)';
ALTER TABLE dragon_tiger MODIFY COLUMN net_buy COMMENT '龙虎榜净买额(BILLBOARD_NET_AMT,元)';
ALTER TABLE dragon_tiger MODIFY COLUMN total_buy COMMENT '买入金额(BILLBOARD_BUY_AMT,元)';
ALTER TABLE dragon_tiger MODIFY COLUMN total_sell COMMENT '卖出金额(BILLBOARD_SELL_AMT,元)';
ALTER TABLE dragon_tiger MODIFY COLUMN billboard_deal_amt COMMENT '龙虎榜成交额(BILLBOARD_DEAL_AMT,元)';
ALTER TABLE dragon_tiger MODIFY COLUMN accum_amount COMMENT '累计成交额(ACCUM_AMOUNT,元)';
ALTER TABLE dragon_tiger MODIFY COLUMN buy_ratio COMMENT '买入占比(BUY_RATIO,%)';
ALTER TABLE dragon_tiger MODIFY COLUMN sell_ratio COMMENT '卖出占比(SELL_RATIO,%)';
ALTER TABLE dragon_tiger MODIFY COLUMN buy_seat COMMENT '买出席位数(BUY_SEAT)';
ALTER TABLE dragon_tiger MODIFY COLUMN sell_seat COMMENT '卖出席位数(SELL_SEAT)';
ALTER TABLE dragon_tiger MODIFY COLUMN buy_seat_new COMMENT '买出席位数(新口径,BUY_SEAT_NEW)';
ALTER TABLE dragon_tiger MODIFY COLUMN sell_seat_new COMMENT '卖出席位数(新口径,SELL_SEAT_NEW)';
ALTER TABLE dragon_tiger MODIFY COLUMN change_rate COMMENT '涨跌幅(CHANGE_RATE,%)';
ALTER TABLE dragon_tiger MODIFY COLUMN close_price COMMENT '收盘价(CLOSE_PRICE,元)';
ALTER TABLE dragon_tiger MODIFY COLUMN turnoverrate COMMENT '换手率(TURNOVERRATE,%)';
ALTER TABLE dragon_tiger MODIFY COLUMN free_market_cap COMMENT '流通市值(FREE_MARKET_CAP,元)';
ALTER TABLE dragon_tiger MODIFY COLUMN market COMMENT '市场(MARKET): SZ/BJ/SH';
ALTER TABLE dragon_tiger MODIFY COLUMN deal_amount_ratio COMMENT '龙虎榜成交额占市场成交额比(DEAL_AMOUNT_RATIO,%)';
ALTER TABLE dragon_tiger MODIFY COLUMN deal_net_ratio COMMENT '龙虎榜净买额占市场成交额比(DEAL_NET_RATIO,%)';
ALTER TABLE dragon_tiger MODIFY COLUMN security_inner_code COMMENT '证券内部编码(SECURITY_INNER_CODE)';
ALTER TABLE dragon_tiger MODIFY COLUMN security_type_code COMMENT '证券类型编码(SECURITY_TYPE_CODE)';
ALTER TABLE dragon_tiger MODIFY COLUMN trade_id COMMENT '交易ID(TRADE_ID,关联主表)';
ALTER TABLE dragon_tiger MODIFY COLUMN trade_market COMMENT '交易市场(TRADE_MARKET)';
ALTER TABLE dragon_tiger MODIFY COLUMN trade_market_code COMMENT '交易市场编码(TRADE_MARKET_CODE)';
ALTER TABLE dragon_tiger MODIFY COLUMN net_bs_amt COMMENT '龙虎榜净买卖额(另一口径,NET_BS_AMT,元)';
ALTER TABLE dragon_tiger MODIFY COLUMN sum_buy_amt COMMENT '买入总额(含非龙虎榜部分,SUM_BUY_AMT,元)';
ALTER TABLE dragon_tiger MODIFY COLUMN sum_sell_amt COMMENT '卖出总额(SUM_SELL_AMT,元)';
ALTER TABLE dragon_tiger MODIFY COLUMN d1_close_adjchrate COMMENT '上榜后1日复权涨跌幅(D1_CLOSE_ADJCHRATE,%)';
ALTER TABLE dragon_tiger MODIFY COLUMN d2_close_adjchrate COMMENT '上榜后2日复权涨跌幅(D2_CLOSE_ADJCHRATE,%)';
ALTER TABLE dragon_tiger MODIFY COLUMN d5_close_adjchrate COMMENT '上榜后5日复权涨跌幅(D5_CLOSE_ADJCHRATE,%)';
ALTER TABLE dragon_tiger MODIFY COLUMN d10_close_adjchrate COMMENT '上榜后10日复权涨跌幅(D10_CLOSE_ADJCHRATE,%)';
ALTER TABLE dragon_tiger MODIFY COLUMN d20_close_adjchrate COMMENT '上榜后20日复权涨跌幅(D20_CLOSE_ADJCHRATE,%)';
ALTER TABLE dragon_tiger MODIFY COLUMN d30_close_adjchrate COMMENT '上榜后30日复权涨跌幅(D30_CLOSE_ADJCHRATE,%)';
ALTER TABLE dragon_tiger MODIFY COLUMN data_source COMMENT '数据来源: 0=东财 1=同花顺';
ALTER TABLE dragon_tiger MODIFY COLUMN src_detail COMMENT '来源URL/接口/备注';
ALTER TABLE dragon_tiger MODIFY COLUMN create_date COMMENT '入库日期';
ALTER TABLE dragon_tiger MODIFY COLUMN update_date COMMENT '更新时间';

-- ---------------------------- dt_detail(龙虎榜席位明细) ----------------------------
ALTER TABLE dt_detail MODIFY COLUMN trade_date COMMENT '交易日期';
ALTER TABLE dt_detail MODIFY COLUMN ts_code COMMENT '股票代码(SECUCODE,带后缀)';
ALTER TABLE dt_detail MODIFY COLUMN seat_name COMMENT '席位名称(OPERATEDEPT_NAME)';
ALTER TABLE dt_detail MODIFY COLUMN seat_type COMMENT '席位类型(OPERATEDEPT_TYPE): 1=营业部 2=游资 3=机构 4=深股通 5=沪股通';
ALTER TABLE dt_detail MODIFY COLUMN rank COMMENT '排名(RANK)';
ALTER TABLE dt_detail MODIFY COLUMN buy COMMENT '买入金额(BUY_AMT,元)';
ALTER TABLE dt_detail MODIFY COLUMN sell COMMENT '卖出金额(SELL_AMT,元)';
ALTER TABLE dt_detail MODIFY COLUMN net_buy COMMENT '净买入(NET_BUY,元)';
ALTER TABLE dt_detail MODIFY COLUMN buy_ratio COMMENT '买入占比(BUY_RATIO,%)';
ALTER TABLE dt_detail MODIFY COLUMN sell_ratio COMMENT '卖出占比(SELL_RATIO,%)';
ALTER TABLE dt_detail MODIFY COLUMN net_buy_ratio COMMENT '净买入占比(NET_BUY_RATIO,%)';
ALTER TABLE dt_detail MODIFY COLUMN trade_amt COMMENT '席位成交额(TRADE_AMT,元)';
ALTER TABLE dt_detail MODIFY COLUMN trade_ratio COMMENT '席位成交额占龙虎榜成交额比(TRADE_RATIO,%)';
ALTER TABLE dt_detail MODIFY COLUMN accum_volume COMMENT '累计成交量(ACCUM_VOLUME,手)';
ALTER TABLE dt_detail MODIFY COLUMN accum_amount COMMENT '累计成交额(ACCUM_AMOUNT,元)';
ALTER TABLE dt_detail MODIFY COLUMN change_rate COMMENT '期间涨跌幅(CHANGE_RATE,%)';
ALTER TABLE dt_detail MODIFY COLUMN turnoverrate_ratio COMMENT '期间换手率(TURNOVERRATE_RATIO,%)';
ALTER TABLE dt_detail MODIFY COLUMN trade_direction COMMENT '交易方向(TRADE_DIRECTION,枚举含义待确认)';
ALTER TABLE dt_detail MODIFY COLUMN statistics_days COMMENT '统计天数(STATISTICS_DAYS)';
ALTER TABLE dt_detail MODIFY COLUMN onlist_times COMMENT '上榜次数(ONLIST_TIMES)';
ALTER TABLE dt_detail MODIFY COLUMN start_date COMMENT '统计起始日期(START_DATE)';
ALTER TABLE dt_detail MODIFY COLUMN end_date COMMENT '统计截止日期(END_DATE)';
ALTER TABLE dt_detail MODIFY COLUMN operate_dept_code COMMENT '席位编号(OPERATEDEPT_CODE)';
ALTER TABLE dt_detail MODIFY COLUMN operate_dept_type COMMENT '席位类型码(OPERATEDEPT_TYPE)';
ALTER TABLE dt_detail MODIFY COLUMN change_type COMMENT '异常类型(CHANGE_TYPE)';
ALTER TABLE dt_detail MODIFY COLUMN explanation COMMENT '上榜原因(EXPLANATION)';
ALTER TABLE dt_detail MODIFY COLUMN trade_id COMMENT '交易ID(TRADE_ID,关联龙虎榜主表)';
ALTER TABLE dt_detail MODIFY COLUMN security_inner_code COMMENT '证券内部编码(SECURITY_INNER_CODE)';
ALTER TABLE dt_detail MODIFY COLUMN sec_type COMMENT '证券类型(STR_MAI,具体含义待确认)';
ALTER TABLE dt_detail MODIFY COLUMN is_institution COMMENT '是否机构席位: 1=是 0=否';
ALTER TABLE dt_detail MODIFY COLUMN is_famous COMMENT '是否知名游资: 1=是 0=否(当前未填充)';
ALTER TABLE dt_detail MODIFY COLUMN data_source COMMENT '数据来源: 0=东财 1=同花顺';
ALTER TABLE dt_detail MODIFY COLUMN src_detail COMMENT '来源URL/接口/备注';
ALTER TABLE dt_detail MODIFY COLUMN create_date COMMENT '入库日期';
ALTER TABLE dt_detail MODIFY COLUMN update_date COMMENT '更新时间';

-- ---------------------------- main_fund_flow(主力资金流) ----------------------------
ALTER TABLE main_fund_flow MODIFY COLUMN trade_date COMMENT '交易日期';
ALTER TABLE main_fund_flow MODIFY COLUMN obj_type COMMENT '维度类型: stock=个股 board=板块 index=指数(当前index未启用)';
ALTER TABLE main_fund_flow MODIFY COLUMN ts_code COMMENT '个股级代码(obj_type=stock时有效)';
ALTER TABLE main_fund_flow MODIFY COLUMN board_code COMMENT '板块级代码(obj_type=board时有效)';
ALTER TABLE main_fund_flow MODIFY COLUMN index_code COMMENT '指数级代码(obj_type=index时有效,当前未启用,固定为0)';
ALTER TABLE main_fund_flow MODIFY COLUMN name COMMENT '名称(股票名/板块名/指数名)';
ALTER TABLE main_fund_flow MODIFY COLUMN main_net COMMENT '主力净流入(=超大单+大单,f62,元)';
ALTER TABLE main_fund_flow MODIFY COLUMN super_big COMMENT '超大单净流入(f66,元)';
ALTER TABLE main_fund_flow MODIFY COLUMN big_net COMMENT '大单净流入(f72,元)';
ALTER TABLE main_fund_flow MODIFY COLUMN mid_net COMMENT '中单净流入(f78,元)';
ALTER TABLE main_fund_flow MODIFY COLUMN small_net COMMENT '小单净流入(f84,元)';
ALTER TABLE main_fund_flow MODIFY COLUMN data_source COMMENT '数据来源: 0=东财 1=同花顺';
ALTER TABLE main_fund_flow MODIFY COLUMN src_detail COMMENT '来源URL/接口/备注';
ALTER TABLE main_fund_flow MODIFY COLUMN create_date COMMENT '入库日期';
ALTER TABLE main_fund_flow MODIFY COLUMN update_date COMMENT '更新时间';

-- ---------------------------- northbound_flow(北向资金) ----------------------------
ALTER TABLE northbound_flow MODIFY COLUMN trade_date COMMENT '交易日期(主键)';
ALTER TABLE northbound_flow MODIFY COLUMN hk_hold_net COMMENT '北向合计净买入(hk2sh+hk2sz,元)';
ALTER TABLE northbound_flow MODIFY COLUMN sh_net COMMENT '沪股通净买入(hk2sh.netBuyAmt,元)';
ALTER TABLE northbound_flow MODIFY COLUMN sz_net COMMENT '深股通净买入(hk2sz.netBuyAmt,元)';
ALTER TABLE northbound_flow MODIFY COLUMN data_source COMMENT '数据来源: 0=东财 1=同花顺';
ALTER TABLE northbound_flow MODIFY COLUMN src_detail COMMENT '来源URL/接口/备注';
ALTER TABLE northbound_flow MODIFY COLUMN create_date COMMENT '入库日期';
ALTER TABLE northbound_flow MODIFY COLUMN update_date COMMENT '更新时间';

-- ========== 五、维表(concept/financial/news_event) ==========

-- ---------------------------- concept(概念主题维表) ----------------------------
ALTER TABLE concept MODIFY COLUMN theme_code COMMENT '概念/题材代码(源自 board_basic.board_code,仅 board_type=3 概念板块)';
ALTER TABLE concept MODIFY COLUMN theme_name COMMENT '概念/题材名称(源自 board_basic.board_name)';
ALTER TABLE concept MODIFY COLUMN theme_type COMMENT '主题类型: 概念/行业/地域(当前固定为"概念")';
ALTER TABLE concept MODIFY COLUMN scarcity COMMENT '题材稀缺性(S7因子,0~1,当前未填充)';
ALTER TABLE concept MODIFY COLUMN imagination COMMENT '题材想象空间(S7因子,0~1,当前未填充)';
ALTER TABLE concept MODIFY COLUMN data_source COMMENT '数据来源: 0=东财 1=同花顺';
ALTER TABLE concept MODIFY COLUMN src_detail COMMENT '来源URL/接口/备注';
ALTER TABLE concept MODIFY COLUMN create_date COMMENT '入库日期';
ALTER TABLE concept MODIFY COLUMN update_date COMMENT '更新时间';

-- ---------------------------- financial(财务报表) ----------------------------
ALTER TABLE financial MODIFY COLUMN ts_code COMMENT '股票代码';
ALTER TABLE financial MODIFY COLUMN end_date COMMENT '财报报告期(季度末日期)';
ALTER TABLE financial MODIFY COLUMN report_type COMMENT '报告类型: Q1/Q2/Q3/年报';
ALTER TABLE financial MODIFY COLUMN ann_date COMMENT '财报公告日期';
ALTER TABLE financial MODIFY COLUMN revenue COMMENT '营业收入(元)';
ALTER TABLE financial MODIFY COLUMN net_profit COMMENT '净利润(元)';
ALTER TABLE financial MODIFY COLUMN net_profit_yoy COMMENT '净利润同比增幅(%)';
ALTER TABLE financial MODIFY COLUMN roe COMMENT '净资产收益率ROE(%)';
ALTER TABLE financial MODIFY COLUMN data_source COMMENT '数据来源: 0=东财 1=同花顺';
ALTER TABLE financial MODIFY COLUMN src_detail COMMENT '来源URL/接口/备注';
ALTER TABLE financial MODIFY COLUMN create_date COMMENT '入库日期';
ALTER TABLE financial MODIFY COLUMN update_date COMMENT '更新时间';

-- ---------------------------- news_event(新闻事件) ----------------------------
ALTER TABLE news_event MODIFY COLUMN event_id COMMENT '事件ID';
ALTER TABLE news_event MODIFY COLUMN event_time COMMENT '事件发布时间';
ALTER TABLE news_event MODIFY COLUMN title COMMENT '新闻标题';
ALTER TABLE news_event MODIFY COLUMN content COMMENT '新闻正文';
ALTER TABLE news_event MODIFY COLUMN source COMMENT '新闻来源';
ALTER TABLE news_event MODIFY COLUMN category COMMENT '事件分类: 政策/行业/公司/题材';
ALTER TABLE news_event MODIFY COLUMN related_board COMMENT '关联板块代码(逗号分隔)';
ALTER TABLE news_event MODIFY COLUMN related_ts_code COMMENT '关联个股代码(逗号分隔)';
ALTER TABLE news_event MODIFY COLUMN sentiment_score COMMENT '情感打分(-1~1,正负向)';
ALTER TABLE news_event MODIFY COLUMN is_policy COMMENT '是否政策类事件: 1=是 0=否';
ALTER TABLE news_event MODIFY COLUMN data_source COMMENT '数据来源: 0=东财 1=同花顺';
ALTER TABLE news_event MODIFY COLUMN src_detail COMMENT '来源URL/接口/备注';
ALTER TABLE news_event MODIFY COLUMN create_date COMMENT '入库日期';
ALTER TABLE news_event MODIFY COLUMN update_date COMMENT '更新时间';

-- ========== 六、计算层表(S1/S2/S4/S6/S7,计算器未实现,字段按命名惯例推断) ==========

-- ---------------------------- sentiment_daily(情绪温度 S2) ----------------------------
ALTER TABLE sentiment_daily MODIFY COLUMN trade_date COMMENT '交易日期(主键)';
ALTER TABLE sentiment_daily MODIFY COLUMN limit_up_cnt COMMENT '[推断]当日涨停股数量';
ALTER TABLE sentiment_daily MODIFY COLUMN limit_down_cnt COMMENT '[推断]当日跌停股数量';
ALTER TABLE sentiment_daily MODIFY COLUMN max_board_pos COMMENT '[推断]当日最高连板高度(最大连板数)';
ALTER TABLE sentiment_daily MODIFY COLUMN yest_limit_ret COMMENT '[推断]昨日涨停股今日平均收益率(%)';
ALTER TABLE sentiment_daily MODIFY COLUMN thermal COMMENT '[推断]情绪热度分值';
ALTER TABLE sentiment_daily MODIFY COLUMN regime COMMENT '[推断]市场情绪状态/周期阶段(如沸/暖/冷)';

-- ---------------------------- theme_factor_daily(题材炒作因子 S7) ----------------------------
ALTER TABLE theme_factor_daily MODIFY COLUMN trade_date COMMENT '交易日期';
ALTER TABLE theme_factor_daily MODIFY COLUMN board_code COMMENT '板块代码';
ALTER TABLE theme_factor_daily MODIFY COLUMN scarcity COMMENT '[推断]题材稀缺性(0~1)';
ALTER TABLE theme_factor_daily MODIFY COLUMN imagination COMMENT '[推断]题材想象空间(0~1)';
ALTER TABLE theme_factor_daily MODIFY COLUMN sudden COMMENT '[推断]题材突发性(催化突发程度)';
ALTER TABLE theme_factor_daily MODIFY COLUMN certainty COMMENT '[推断]题材确定性(逻辑兑现确定度)';
ALTER TABLE theme_factor_daily MODIFY COLUMN min_resist COMMENT '[推断]最小阻力(上涨阻力/承接力度)';
ALTER TABLE theme_factor_daily MODIFY COLUMN total COMMENT '[推断]题材综合因子总分';

-- ---------------------------- trend_candidate_daily(趋势股候选 S6) ----------------------------
ALTER TABLE trend_candidate_daily MODIFY COLUMN trade_date COMMENT '交易日期';
ALTER TABLE trend_candidate_daily MODIFY COLUMN ts_code COMMENT '股票代码';
ALTER TABLE trend_candidate_daily MODIFY COLUMN feature_hit COMMENT '[推断]是否触发趋势形态特征: 1=是 0=否';
ALTER TABLE trend_candidate_daily MODIFY COLUMN rs_vs_index COMMENT '[推断]个股相对指数的相对强度(RS评级)';
ALTER TABLE trend_candidate_daily MODIFY COLUMN confirmed COMMENT '趋势是否确认: 1=是 0=否';

-- ---------------------------- four_dimension_daily(四维度评分 S1) ----------------------------
ALTER TABLE four_dimension_daily MODIFY COLUMN trade_date COMMENT '交易日期(主键)';
ALTER TABLE four_dimension_daily MODIFY COLUMN tech COMMENT '[推断]技术面评分';
ALTER TABLE four_dimension_daily MODIFY COLUMN sentiment COMMENT '[推断]情绪面评分';
ALTER TABLE four_dimension_daily MODIFY COLUMN fund COMMENT '[推断]资金面评分';
ALTER TABLE four_dimension_daily MODIFY COLUMN policy COMMENT '[推断]政策面评分';
ALTER TABLE four_dimension_daily MODIFY COLUMN composite COMMENT '[推断]四维度综合评分';
ALTER TABLE four_dimension_daily MODIFY COLUMN worth_trade COMMENT '是否值得交易(综合阈值判定): 1=是 0=否';
ALTER TABLE four_dimension_daily MODIFY COLUMN note COMMENT '评分备注/原因说明';

-- ---------------------------- mainline_daily(主线识别 S4) ----------------------------
ALTER TABLE mainline_daily MODIFY COLUMN trade_date COMMENT '交易日期';
ALTER TABLE mainline_daily MODIFY COLUMN board_code COMMENT '板块代码';
ALTER TABLE mainline_daily MODIFY COLUMN main_level COMMENT '[推断]主线等级(如强主线/次主线/无)';
ALTER TABLE mainline_daily MODIFY COLUMN strength COMMENT '[推断]主线强度分值';
ALTER TABLE mainline_daily MODIFY COLUMN rank COMMENT '[推断]主线排名';

-- ---------------------------- leader_pool_daily(龙头池 S4) ----------------------------
ALTER TABLE leader_pool_daily MODIFY COLUMN trade_date COMMENT '交易日期';
ALTER TABLE leader_pool_daily MODIFY COLUMN ts_code COMMENT '股票代码';
ALTER TABLE leader_pool_daily MODIFY COLUMN board_code COMMENT '所属板块代码';
ALTER TABLE leader_pool_daily MODIFY COLUMN board_pos COMMENT '[推断]角色(龙头/跟风/补涨等)';
ALTER TABLE leader_pool_daily MODIFY COLUMN role COMMENT '[推断]龙头角色(枚举值待确认)';
ALTER TABLE leader_pool_daily MODIFY COLUMN score COMMENT '[推断]龙头综合评分';

-- ========== 七、任务配置表 ==========

-- ---------------------------- stock_task_config(股票任务配置) ----------------------------
ALTER TABLE stock_task_config MODIFY COLUMN type COMMENT '任务类型(如 minute=分时)';
ALTER TABLE stock_task_config MODIFY COLUMN code COMMENT '股票代码(600000.SH)';
ALTER TABLE stock_task_config MODIFY COLUMN stock_name COMMENT '股票名称';
ALTER TABLE stock_task_config MODIFY COLUMN status COMMENT '启用状态: 1=启用 0=禁用';
ALTER TABLE stock_task_config MODIFY COLUMN create_date COMMENT '创建日期';
ALTER TABLE stock_task_config MODIFY COLUMN update_date COMMENT '更新时间';
