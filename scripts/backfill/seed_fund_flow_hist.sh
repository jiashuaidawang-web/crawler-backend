#!/bin/bash
# 主力资金流历史回填播种：MAIN_FUND_STOCK_HIST × 全市场股票 + MAIN_FUND_BOARD_HIST × 板块。
# fflow/daykline 一次拿满 ~120 交易日窗口（2026-04-03 起）。
export MSYS_NO_PATHCONV=1
CK="http://localhost:8123/"; AUTH="default:pamirs@123"
q() { curl -s --max-time 120 --user "$AUTH" --data-binary "$1" "$CK"; }

# 已有任务（冒烟已验的跳过）
EXIST=$(docker exec opengauss-lite psql -U dbuser -d postgres -t -A -c \
  "SELECT unique_key FROM crawl_task WHERE task_type IN ('MAIN_FUND_STOCK_HIST','MAIN_FUND_BOARD_HIST');" | sort -u)

SQL_FILE=/d/Development/IDEAWorkSpace/Github/new/crawler-backend/scripts/backfill/seed_fflow.sql
> "$SQL_FILE"
i=0
N_STOCK=0
N_BOARD=0

gen_row() { # $1=task_type $2=code $3=params_key
  local UK="$1|1|$2"
  if echo "$EXIST" | grep -qx "$UK"; then return; fi
  if [ $((i % 500)) -eq 0 ]; then
    [ $i -gt 0 ] && echo ";" >> "$SQL_FILE"
    echo -n "INSERT INTO crawl_task (task_type, source, params_json, status, priority, retry_count, max_retry, unique_key, created_at, updated_at) VALUES " >> "$SQL_FILE"
    sep=""
  else
    sep=","
  fi
  echo -n "${sep}('$1', 1, '{\"$3\":\"$2\"}', 'PENDING', 4, 0, 3, '$UK', now(), now())" >> "$SQL_FILE"
  i=$((i+1))
}

# 个股：stock_daily 全市场
for C in $(q "SELECT DISTINCT ts_code FROM crawler.stock_daily FORMAT TSV"); do
  gen_row "MAIN_FUND_STOCK_HIST" "$C" "tsCode"
done
N_STOCK=$i

# 板块：main_fund_flow 已跟踪的板块（与每日快照口径一致），续写同一文件
for C in $(q "SELECT DISTINCT board_code FROM crawler.main_fund_flow WHERE obj_type='board' FORMAT TSV"); do
  gen_row "MAIN_FUND_BOARD_HIST" "$C" "boardCode"
done
N_BOARD=$((i - N_STOCK))
echo ";" >> "$SQL_FILE"

echo "stocks=$N_STOCK boards=$N_BOARD total=$i"
docker exec -i opengauss-lite psql -U dbuser -d postgres -v ON_ERROR_STOP=1 < "$SQL_FILE" 2>&1 | grep -c "INSERT"
echo "=== 验证 ==="
docker exec opengauss-lite psql -U dbuser -d postgres -t -c "SELECT task_type, status, count(*) FROM crawl_task WHERE task_type LIKE 'MAIN_FUND%_HIST' GROUP BY task_type, status ORDER BY task_type;"
