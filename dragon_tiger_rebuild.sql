-- ============================================================================
-- dragon_tiger 重建带注释（DROP + CREATE，不保留数据）
-- 执行：clickhouse-client --host <ck> --port=8123 -d crawler < dragon_tiger_rebuild.sql
-- ============================================================================

DROP TABLE IF NOT EXISTS dragon_tiger;

CREATE TABLE dragon_tiger (
    trade_date              Date NOT NULL COMMENT '上榜交易日',
    ts_code                 String NOT NULL COMMENT '带后缀代码(优先 SECUCODE,如 000779.SZ)',
    stock_name              Nullable(String) COMMENT '股票名称',
    reason                  Nullable(String) COMMENT '上榜原因(简,如"1家机构买入,成功率21.13%")',
    explanation             Nullable(String) COMMENT '上榜原因(详,如"日涨幅偏离值达到7%的前5只证券")',
    abnormal_type           Nullable(String) COMMENT '异常类型码(CHANGE_TYPE 原始码,如 137001002001001)',
    net_buy                 Nullable(Decimal64(2)) COMMENT '龙虎榜净买额(元)',
    total_buy               Nullable(Decimal64(2)) COMMENT '龙虎榜买入金额(元)',
    total_sell              Nullable(Decimal64(2)) COMMENT '龙虎榜卖出金额(元)',
    billboard_deal_amt      Nullable(Decimal64(2)) COMMENT '龙虎榜成交额(元)',
    accum_amount            Nullable(Decimal64(2)) COMMENT '累计成交额(元)',
    buy_ratio               Nullable(Decimal64(4)) COMMENT '买入金额占比(%)',
    sell_ratio              Nullable(Decimal64(4)) COMMENT '卖出金额占比(%)',
    buy_seat                Nullable(Int32) COMMENT '买入席位数',
    sell_seat               Nullable(Int32) COMMENT '卖出席位数',
    buy_seat_new            Nullable(Int32) COMMENT '买入席位数(新口径)',
    sell_seat_new           Nullable(Int32) COMMENT '卖出席位数(新口径)',
    change_rate             Nullable(Decimal64(4)) COMMENT '当日涨跌幅(%)',
    close_price             Nullable(Decimal64(4)) COMMENT '收盘价(元)',
    turnoverrate            Nullable(Decimal64(4)) COMMENT '换手率(%)',
    free_market_cap         Nullable(Decimal64(2)) COMMENT '流通市值(元)',
    market                  Nullable(String) COMMENT '市场(SZ/BJ/SH)',
    deal_amount_ratio       Nullable(Decimal64(4)) COMMENT '龙虎榜成交额占比(%)',
    deal_net_ratio          Nullable(Decimal64(4)) COMMENT '龙虎榜净买额占比(%)',
    security_inner_code     Nullable(String) COMMENT '证券内部编码',
    security_type_code      Nullable(String) COMMENT '证券类型码(如 058001001)',
    trade_id                Nullable(Int64) COMMENT '交易记录ID',
    trade_market            Nullable(String) COMMENT '交易市场(如"深交所主板")',
    trade_market_code       Nullable(String) COMMENT '交易市场码(如 069001002001)',
    net_bs_amt              Nullable(Decimal64(2)) COMMENT '龙虎榜净买卖额(另一口径,元)',
    sum_buy_amt             Nullable(Decimal64(2)) COMMENT '买入总额(含非龙虎榜部分,元)',
    sum_sell_amt            Nullable(Decimal64(2)) COMMENT '卖出总额(元)',
    d1_close_adjchrate      Nullable(Decimal64(4)) COMMENT '上榜后1日复权涨跌幅(%)',
    d2_close_adjchrate      Nullable(Decimal64(4)) COMMENT '上榜后2日复权涨跌幅(%)',
    d5_close_adjchrate      Nullable(Decimal64(4)) COMMENT '上榜后5日复权涨跌幅(%)',
    d10_close_adjchrate     Nullable(Decimal64(4)) COMMENT '上榜后10日复权涨跌幅(%)',
    d20_close_adjchrate     Nullable(Decimal64(4)) COMMENT '上榜后20日复权涨跌幅(%)',
    d30_close_adjchrate     Nullable(Decimal64(4)) COMMENT '上榜后30日复权涨跌幅(%)',
    data_source             UInt8 NOT NULL DEFAULT 0 COMMENT '数据来源: 0=东财 1=同花顺',
    src_detail              Nullable(String) COMMENT '来源URL/接口/备注',
    create_date             Nullable(Date) COMMENT '入库日期',
    update_date             Nullable(DateTime) COMMENT '更新时间'
) ENGINE = MergeTree()
PARTITION BY toYYYYMM(trade_date)
ORDER BY (ts_code, trade_date)
SETTINGS index_granularity = 8192
COMMENT 'A8 龙虎榜(S3 主力博弈)';
