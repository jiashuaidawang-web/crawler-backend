#!/bin/bash
# 重建 stock_daily：月分区→年分区。逐月 ATTACH→INSERT SELECT→DETACH。
# 前提：所有 parts 已在 detached/，server 已启动且 STOP MERGES，内存地板≈0。
export MSYS_NO_PATHCONV=1
CK="http://localhost:8123/"; AUTH="default:pamirs@123"
LOG="D:/Development/IDEAWorkSpace/Github/new/crawler-backend/scripts/backfill/rebuild_progress.log"
q() { curl -s --max-time 900 --user "$AUTH" --data-binary "$1" "$CK"; }

echo "start $(date '+%H:%M:%S')" > "$LOG"

# 0. 建新表（年分区）——上次空跑没建成，IF NOT EXISTS 兜底
R=$(q "CREATE TABLE IF NOT EXISTS crawler.stock_daily_new
(
    \`trade_date\` Date COMMENT '交易日期',
    \`ts_code\` String,
    \`stock_name\` Nullable(String),
    \`open\` Nullable(Decimal(18, 4)),
    \`high\` Nullable(Decimal(18, 4)),
    \`low\` Nullable(Decimal(18, 4)),
    \`close\` Nullable(Decimal(18, 4)),
    \`pre_close\` Nullable(Decimal(18, 4)),
    \`pct_chg\` Nullable(Decimal(18, 4)),
    \`vol\` Nullable(Decimal(18, 4)),
    \`amount\` Nullable(Decimal(18, 4)),
    \`turnover\` Nullable(Decimal(18, 4)),
    \`total_mv\` Nullable(Decimal(18, 2)),
    \`circ_mv\` Nullable(Decimal(18, 2)),
    \`pe\` Nullable(Decimal(18, 4)),
    \`is_limit_up\` Nullable(UInt8),
    \`is_limit_down\` Nullable(UInt8),
    \`chg_amount\` Nullable(Decimal(18, 4)),
    \`amplitude\` Nullable(Decimal(18, 4)),
    \`volume_ratio\` Nullable(Decimal(18, 4)),
    \`avg_price\` Nullable(Decimal(18, 4)),
    \`main_net\` Nullable(Decimal(18, 2)),
    \`super_big\` Nullable(Decimal(18, 2)),
    \`big_net\` Nullable(Decimal(18, 2)),
    \`mid_net\` Nullable(Decimal(18, 2)),
    \`small_net\` Nullable(Decimal(18, 2)),
    \`pe_static\` Nullable(Decimal(18, 4)),
    \`leader_code\` Nullable(String),
    \`industry_code\` Nullable(String),
    \`concept_code\` Nullable(String),
    \`market_code\` Nullable(Int32),
    \`turn_speed\` Nullable(Decimal(18, 4)),
    \`velocity\` Nullable(Decimal(18, 4)),
    \`is_new_high\` Nullable(UInt8),
    \`chg_60d\` Nullable(Decimal(18, 4)),
    \`seal_fund\` Nullable(Decimal(18, 2)),
    \`board_days\` Nullable(Int32),
    \`board_stat\` Nullable(String),
    \`first_seal_time\` Nullable(String),
    \`last_seal_time\` Nullable(String),
    \`limit_type\` Nullable(Int32),
    \`reserved_f24\` Nullable(Decimal(18, 4)),
    \`reserved_f25\` Nullable(Decimal(18, 4)),
    \`reserved_f107\` Nullable(Decimal(18, 4)),
    \`reserved_f136\` Nullable(Decimal(18, 2)),
    \`reserved_f173\` Nullable(Decimal(18, 4)),
    \`data_source\` UInt8,
    \`src_detail\` Nullable(String),
    \`create_date\` Nullable(Date),
    \`update_date\` Nullable(DateTime),
    \`_ver\` DateTime MATERIALIZED assumeNotNull(coalesce(update_date, toDateTime(0)))
)
ENGINE = ReplacingMergeTree(_ver)
PARTITION BY toYear(trade_date)
ORDER BY (ts_code, trade_date, data_source)
SETTINGS index_granularity = 8192, max_parts_in_total = 100000, parts_to_delay_insert = 2500, parts_to_throw_insert = 5000")
[ -n "$R" ] && echo "$(date '+%H:%M:%S') CREATE ERROR: $R" >> "$LOG"
echo "$(date '+%H:%M:%S') create table ok" >> "$LOG"

# 1. 从 detached/ 取分区列表（真实来源，避免假设）
PIDS=$(docker exec astock-clickhouse-win bash -c "ls /var/lib/clickhouse/store/493/49346d14-eb5a-495a-97d0-07e2fe9078b9/detached/ | cut -d_ -f1 | sort -u")
N_TOTAL=$(echo "$PIDS" | wc -l)
echo "$(date '+%H:%M:%S') partitions to process: $N_TOTAL" >> "$LOG"

TOTAL=0; N=0
for P in $PIDS; do
  N=$((N+1))
  Y=${P:0:4}; M=${P:4:2}
  NEXT=$(date -d "$Y-$M-01 +1 month" +%Y-%m-%d 2>/dev/null)
  if [ -z "$NEXT" ]; then echo "$(date '+%H:%M:%S') [$P] date calc FAILED" >> "$LOG"; continue; fi
  T0=$(date +%s)

  R=$(q "ALTER TABLE crawler.stock_daily ATTACH PARTITION $P")
  if [ -n "$R" ]; then echo "$(date '+%H:%M:%S') [$P] ATTACH ERR: $R" >> "$LOG"; continue; fi

  R=$(q "INSERT INTO crawler.stock_daily_new SELECT * FROM crawler.stock_daily WHERE trade_date >= '$Y-$M-01' AND trade_date < '$NEXT'")
  if [ -n "$R" ]; then
    echo "$(date '+%H:%M:%S') [$P] INSERT ERR: $R" >> "$LOG"
    q "ALTER TABLE crawler.stock_daily DETACH PARTITION $P" > /dev/null 2>&1
    continue
  fi

  C=$(q "SELECT count() FROM crawler.stock_daily_new WHERE trade_date >= '$Y-$M-01' AND trade_date < '$NEXT' FORMAT TabSeparated")
  TOTAL=$((TOTAL+C))

  R=$(q "ALTER TABLE crawler.stock_daily DETACH PARTITION $P")
  [ -n "$R" ] && echo "$(date '+%H:%M:%S') [$P] DETACH WARN: $R" >> "$LOG"

  T1=$(date +%s)
  echo "$(date '+%H:%M:%S') [$N/$N_TOTAL] $P rows=$C elapsed=$((T1-T0))s total=$TOTAL" >> "$LOG"
done

# 2. 终验
NEW=$(q "SELECT count() FROM crawler.stock_daily_new FORMAT TabSeparated")
UNIQ=$(q "SELECT uniqExact(ts_code, trade_date, data_source) FROM crawler.stock_daily_new FORMAT TabSeparated")
echo "$(date '+%H:%M:%S') VERIFY new_total=$NEW sum_cycles=$TOTAL uniq_keys=$UNIQ" >> "$LOG"
echo "done $(date '+%H:%M:%S')" >> "$LOG"
