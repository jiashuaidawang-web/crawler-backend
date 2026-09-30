#!/bin/bash
# 龙虎榜主表历史回填播种：DRAGON_TIGER 任务 × 交易日(2004-09-01 → 2026-09-24)。
# 排除 dragon_tiger 已有数据的日期 + crawl_task 已有活跃任务的日期。
export MSYS_NO_PATHCONV=1
CK="http://localhost:8123/"; AUTH="default:pamirs@123"
q() { curl -s --max-time 120 --user "$AUTH" --data-binary "$1" "$CK"; }

# 1. 交易日列表
DAYS=$(q "SELECT trade_date FROM crawler.stock_trade_calendar WHERE trade_date >= '2004-09-01' AND trade_date <= '2026-09-24' AND is_trading=1 ORDER BY trade_date FORMAT TSV")
N_DAYS=$(echo "$DAYS" | wc -l)

# 2. 已有数据的日期
HAVE=$(q "SELECT DISTINCT trade_date FROM crawler.dragon_tiger FORMAT TSV" | sort -u)
N_HAVE=$(echo "$HAVE" | wc -l)

# 3. crawl_task 已有活跃任务的日期（PENDING/CLAIMED/RETRY/SUCCESS）
HAVE_TASK=$(docker exec opengauss-lite psql -U dbuser -d postgres -t -A -c \
  "SELECT DISTINCT substring(unique_key from '[0-9]{4}-[0-9]{2}-[0-9]{2}') FROM crawl_task WHERE task_type='DRAGON_TIGER' AND status IN ('PENDING','CLAIMED','RETRY','SUCCESS');" | sort -u)
N_HT=$(echo "$HAVE_TASK" | grep -c '2026\|200' || true)

# 4. 差集
TO_SEED=$(comm -23 <(echo "$DAYS") <(cat <(echo "$HAVE") <(echo "$HAVE_TASK") | sort -u))
N_SEED=$(echo "$TO_SEED" | grep -cE '^[0-9]{4}-' || true)
echo "trading=$N_DAYS have_data=$N_HAVE have_task=$N_HT to_seed=$N_SEED"

# 5. 生成 INSERT 并分批执行
SQL_FILE=/d/Development/IDEAWorkSpace/Github/new/crawler-backend/scripts/backfill/seed_dt.sql
> "$SQL_FILE"
i=0
for D in $TO_SEED; do
  case "$D" in
    20*) ;;
    *) continue ;;
  esac
  if [ $((i % 500)) -eq 0 ]; then
    [ $i -gt 0 ] && echo ";" >> "$SQL_FILE"
    echo -n "INSERT INTO crawl_task (task_type, source, params_json, status, priority, retry_count, max_retry, unique_key, created_at, updated_at) VALUES " >> "$SQL_FILE"
    sep=""
  else
    sep=","
  fi
  echo -n "${sep}('DRAGON_TIGER', 1, '{\"tradeDate\":\"$D\"}', 'PENDING', 5, 0, 3, 'DRAGON_TIGER|1|$D', now(), now())" >> "$SQL_FILE"
  i=$((i+1))
done
echo ";" >> "$SQL_FILE"
echo "generated $i rows"

# 6. 执行
docker exec -i opengauss-lite psql -U dbuser -d postgres -v ON_ERROR_STOP=1 < "$SQL_FILE" 2>&1 | tail -5
echo "=== 验证 ==="
docker exec opengauss-lite psql -U dbuser -d postgres -t -c "SELECT status, count(*) FROM crawl_task WHERE task_type='DRAGON_TIGER' GROUP BY status;"
