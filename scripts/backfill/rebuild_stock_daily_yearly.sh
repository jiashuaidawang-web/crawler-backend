#!/bin/bash
# 重建 stock_daily：月分区(430个,part爆炸根源) → 年分区(37个)
# 分年 INSERT SELECT，每批只读该年的月分区parts，内存有界。
export MSYS_NO_PATHCONV=1
CK="http://localhost:8123/"; AUTH="default:pamirs@123"
LOG="D:/Development/IDEAWorkSpace/Github/new/crawler-backend/scripts/backfill/rebuild_progress.log"
q() { curl -s --max-time 600 --user "$AUTH" --data-binary "$1" "$CK"; }

echo "start $(date '+%H:%M:%S')" > "$LOG"

# 1. 建新表（同schema，年分区）
q "CREATE TABLE IF NOT EXISTS crawler.stock_daily_new
(
    \`trade_date\` Date COMMENT '交易日期',
    \`ts_code\` String COMMENT '股票代码(带后缀,如 600000.SH)',
    \`stock_name\` Nullable(String) COMMENT '股票名称',
    \`open\` Nullable(Decimal(18, 4)) COMMENT '开盘价(f17,元)',
    \`high\` Nullable(Decimal(18, 4)) COMMENT '最高价(f15,元)',
    \`low\` Nullable(Decimal(18, 4)) COMMENT '最低价(f16,元)',
    \`close\` Nullable(Decimal(18, 4)) COMMENT '收盘价(f2,元)',
    \`pre_close\` Nullable(Decimal(18, 4)) COMMENT '昨收价(f18,元)',
    \`pct_chg\` Nullable(Decimal(18, 4)) COMMENT '涨跌幅(f3,%)',
    \`vol\` Nullable(Decimal(18, 4)) COMMENT '成交量(f5,手)',
    \`amount\` Nullable(Decimal(18, 4)) COMMENT '成交额(f6,元)',
    \`turnover\` Nullable(Decimal(18, 4)) COMMENT '换手率(f8,%)',
    \`total_mv\` Nullable(Decimal(18, 2)) COMMENT '总市值(f20,元)',
    \`circ_mv\` Nullable(Decimal(18, 2)) COMMENT '流通市值(f21,元)',
    \`pe\` Nullable(Decimal(18, 4)) COMMENT '市盈率TTM(f9)',
    \`is_limit_up\` Nullable(UInt8) COMMENT '是否涨停(1/0,pct_chg≥9.8近似判定)',
    \`is_limit_down\` Nullable(UInt8) COMMENT '是否跌停(1/0,pct_chg≤-9.8近似判定)',
    \`chg_amount\` Nullable(Decimal(18, 4)) COMMENT '涨跌额(f4,元)',
    \`amplitude\` Nullable(Decimal(18, 4)) COMMENT '振幅(f7,%)',
    \`volume_ratio\` Nullable(Decimal(18, 4)) COMMENT '量比(f10)',
    \`avg_price\` Nullable(Decimal(18, 4)) COMMENT '均价(预留字段,暂无数据)',
    \`main_net\` Nullable(Decimal(18, 2)) COMMENT '主力净流入(f62,元)',
    \`super_big\` Nullable(Decimal(18, 2)) COMMENT '超大单净流入(f66,元)',
    \`big_net\` Nullable(Decimal(18, 2)) COMMENT '大单净流入(f72,元)',
    \`mid_net\` Nullable(Decimal(18, 2)) COMMENT '中单净流入(f78,元)',
    \`small_net\` Nullable(Decimal(18, 2)) COMMENT '小单净流入(f84,元)',
    \`pe_static\` Nullable(Decimal(18, 4)) COMMENT '静态市盈率(f115)',
    \`leader_code\` Nullable(String) COMMENT '领涨股代码(预留字段,暂无数据)',
    \`industry_code\` Nullable(String) COMMENT '所属行业代码(预留字段,暂无数据)',
    \`concept_code\` Nullable(String) COMMENT '所属概念代码(预留字段,暂无数据)',
    \`market_code\` Nullable(Int32) COMMENT '市场码(f152): 0=深 1=沪 2=京',
    \`turn_speed\` Nullable(Decimal(18, 4)) COMMENT '涨速另一口径(f22,%)',
    \`velocity\` Nullable(Decimal(18, 4)) COMMENT '涨速(f11,%)',
    \`is_new_high\` Nullable(UInt8) COMMENT '是否新高(预留字段,暂无数据)',
    \`chg_60d\` Nullable(Decimal(18, 4)) COMMENT '60日涨跌幅(f23,%)',
    \`seal_fund\` Nullable(Decimal(18, 2)) COMMENT '封单资金(预留字段,暂无数据)',
    \`board_days\` Nullable(Int32) COMMENT '连板天数(预留字段,暂无数据)',
    \`board_stat\` Nullable(String) COMMENT '涨停统计(预留字段,暂无数据)',
    \`first_seal_time\` Nullable(String) COMMENT '首次封板时间(预留字段,暂无数据)',
    \`last_seal_time\` Nullable(String) COMMENT '最后封板时间(预留字段,暂无数据)',
    \`limit_type\` Nullable(Int32) COMMENT '涨停类型(预留字段,暂无数据)',
    \`reserved_f24\` Nullable(Decimal(18, 4)) COMMENT '年初至今涨跌幅(f24,%)',
    \`reserved_f25\` Nullable(Decimal(18, 4)) COMMENT 'f25(含义待确认)',
    \`reserved_f107\` Nullable(Decimal(18, 4)) COMMENT 'f107(预留字段,暂无数据)',
    \`reserved_f136\` Nullable(Decimal(18, 2)) COMMENT 'f136(预留字段,暂无数据)',
    \`reserved_f173\` Nullable(Decimal(18, 4)) COMMENT '涨速(f173,%)',
    \`data_source\` UInt8 COMMENT '数据来源: 0=东财 1=同花顺',
    \`src_detail\` Nullable(String) COMMENT '来源URL/接口/备注',
    \`create_date\` Nullable(Date) COMMENT '入库日期',
    \`update_date\` Nullable(DateTime) COMMENT '更新时间',
    \`_ver\` DateTime MATERIALIZED assumeNotNull(coalesce(update_date, toDateTime(0)))
)
ENGINE = ReplacingMergeTree(_ver)
PARTITION BY toYYYY(trade_date)
ORDER BY (ts_code, trade_date, data_source)
SETTINGS index_granularity = 8192, max_parts_in_total = 100000, parts_to_delay_insert = 2500, parts_to_throw_insert = 5000" >> "$LOG" 2>&1
echo "$(date '+%H:%M:%S') create table done" >> "$LOG"

# 2. 逐年拷贝
TOTAL=0
for Y in $(seq 1990 2026); do
  Y2=$((Y+1))
  T0=$(date +%s)
  R=$(q "INSERT INTO crawler.stock_daily_new SELECT * FROM crawler.stock_daily WHERE trade_date >= '$Y-01-01' AND trade_date < '$Y2-01-01'")
  T1=$(date +%s)
  if [ -n "$R" ]; then
    echo "$(date '+%H:%M:%S') [$Y] ERROR: $R" >> "$LOG"
    echo "YEAR $Y FAILED" >> "$LOG"
    break
  fi
  C=$(q "SELECT count() FROM crawler.stock_daily_new WHERE trade_date >= '$Y-01-01' AND trade_date < '$Y2-01-01' FORMAT TabSeparated")
  TOTAL=$((TOTAL+C))
  echo "$(date '+%H:%M:%S') [$Y] ok rows=$C elapsed=$((T1-T0))s total=$TOTAL" >> "$LOG"
done

# 3. 总量核对
OLD=$(q "SELECT count() FROM crawler.stock_daily FORMAT TabSeparated")
NEW=$(q "SELECT count() FROM crawler.stock_daily_new FORMAT TabSeparated")
echo "$(date '+%H:%M:%S') VERIFY old=$OLD new=$NEW (相等则可切换)" >> "$LOG"
echo "done $(date '+%H:%M:%S')" >> "$LOG"
