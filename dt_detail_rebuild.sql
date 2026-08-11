-- ============================================================================
-- dt_detail 重建带注释（表无数据，直接 DROP + CREATE）
-- 执行：clickhouse-client --host <ck> --port=8123 -d crawler < dt_detail_rebuild.sql
-- ============================================================================

DROP TABLE IF NOT EXISTS dt_detail;

CREATE TABLE dt_detail (
    trade_date              Date NOT NULL COMMENT '上榜交易日',
    ts_code                 String NOT NULL COMMENT '带后缀代码(如 000603.SZ)',
    seat_name               String NOT NULL COMMENT '席位/营业部名称(如"机构专用"、"拉萨团结路第一证券营业部")',
    seat_type               Nullable(String) COMMENT '席位类型(2=游资 3=机构 等,OPERATEDEPT_TYPE)',
    `rank`                  Nullable(Int32) COMMENT '席位排名(RANK)',
    buy                     Nullable(Decimal64(2)) COMMENT '买入金额(元,BUY_AMT)',
    sell                    Nullable(Decimal64(2)) COMMENT '卖出金额(元,SELL_AMT)',
    net_buy                 Nullable(Decimal64(2)) COMMENT '净买入(元,NET_BUY=BUY-SELL)',
    buy_ratio               Nullable(Decimal64(4)) COMMENT '买入占比(%,BUY_RATIO)',
    sell_ratio              Nullable(Decimal64(4)) COMMENT '卖出占比(%,SELL_RATIO)',
    net_buy_ratio           Nullable(Decimal64(4)) COMMENT '净买入占比(%,NET_BUY_RATIO)',
    trade_amt               Nullable(Decimal64(2)) COMMENT '席位成交额(元,TRADE_AMT)',
    trade_ratio             Nullable(Decimal64(4)) COMMENT '成交额占比(%,TRADE_RATIO)',
    accum_volume            Nullable(Decimal64(2)) COMMENT '累计成交量(手,ACCUM_VOLUME)',
    accum_amount            Nullable(Decimal64(2)) COMMENT '累计成交额(元,ACCUM_AMOUNT)',
    change_rate             Nullable(Decimal64(4)) COMMENT '期间涨跌幅(%,CHANGE_RATE)',
    turnoverrate_ratio      Nullable(Decimal64(4)) COMMENT '期间换手率(%,TURNOVERRATE_RATIO)',
    trade_direction         Nullable(Int32) COMMENT '交易方向(TRADE_DIRECTION:1=净买入 etc)',
    statistics_days         Nullable(Int32) COMMENT '统计天数(STATISTICS_DAYS)',
    onlist_times            Nullable(Int32) COMMENT '上榜次数(ONLIST_TIMES)',
    start_date              Nullable(Date) COMMENT '统计起始日(START_DATE)',
    end_date                Nullable(Date) COMMENT '统计截止日(END_DATE)',
    operate_dept_code       Nullable(String) COMMENT '席位编号(OPERATEDEPT_CODE)',
    operate_dept_type       Nullable(Int32) COMMENT '席位类型码(OPERATEDEPT_TYPE)',
    change_type             Nullable(String) COMMENT '异常类型码(CHANGE_TYPE)',
    explanation             Nullable(String) COMMENT '上榜原因(EXPLANATION)',
    trade_id                Nullable(Int64) COMMENT '关联主表交易ID(TRADE_ID)',
    security_inner_code     Nullable(String) COMMENT '证券内部编码(SECURITY_INNER_CODE)',
    sec_type                Nullable(Int32) COMMENT '证券类型(STR_MAI)',
    data_source             UInt8 NOT NULL DEFAULT 0 COMMENT '数据来源: 0=东财 1=同花顺',
    src_detail              Nullable(String) COMMENT '来源URL/接口/备注',
    create_date             Nullable(Date) COMMENT '入库日期',
    update_date             Nullable(DateTime) COMMENT '更新时间'
) ENGINE = MergeTree()
PARTITION BY toYYYYMM(trade_date)
ORDER BY (ts_code, trade_date, seat_name)
SETTINGS index_granularity = 8192
COMMENT 'A9 龙虎榜席位明细(S3 破除主力迷信:知名游资≠必胜)';
