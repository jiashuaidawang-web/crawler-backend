#!/bin/bash
# 龙虎榜席位明细回填播种：dragon_tiger 全部 TRADE_ID → DRAGON_TIGER_DETAIL 任务。
# 前提：① DRAGON_TIGER 历史回填已全部终态。
# 排除：dt_detail 已有 trade_id（CK 侧过滤）+ crawl_task 已有任务（PG NOT EXISTS）。
# 优先级 3（低于 ② 的 4），避免 20 万级任务饿死其他队列。
# 性能：SQL 方案（TSV 导出 + \copy + INSERT SELECT），24.6 万行 ~10 秒。
#   —— bash 逐行循环方案（grep -qx 每行 spawn 子进程）要 4 小时，已弃用。
export MSYS_NO_PATHCONV=1
CK="http://localhost:8123/"; AUTH="default:pamirs@123"
DIR=/d/Development/IDEAWorkSpace/Github/new/crawler-backend/scripts/backfill

# 0. 前置检查：DRAGON_TIGER 是否全部终态
LEFT=$(docker exec opengauss-lite psql -U dbuser -d postgres -t -A -c \
  "SELECT count(*) FROM crawl_task WHERE task_type='DRAGON_TIGER' AND status NOT IN ('SUCCESS','FAILED','DEAD');")
if [ "$LEFT" != "0" ]; then echo "ABORT: DRAGON_TIGER 还有 $LEFT 个未完成任务"; exit 1; fi

# 1. CK 导出待回填 (trade_id, trade_date)：dragon_tiger 有而 dt_detail 没有
curl -s --max-time 300 --user "$AUTH" --data-binary "SELECT DISTINCT trade_id, toString(trade_date) FROM crawler.dragon_tiger
WHERE trade_id IS NOT NULL
  AND trade_id NOT IN (SELECT DISTINCT trade_id FROM crawler.dt_detail WHERE trade_id IS NOT NULL)
ORDER BY trade_date, trade_id FORMAT TSV" "$CK" > "$DIR/dt_pairs.tsv"
echo "pairs=$(wc -l < "$DIR/dt_pairs.tsv")"

# 2. 拷进容器 → 临时表 → INSERT SELECT（拼接/排重全在 SQL 内）
docker cp "$DIR/dt_pairs.tsv" opengauss-lite:/tmp/dt_pairs.tsv
docker exec -i opengauss-lite psql -U dbuser -d postgres -v ON_ERROR_STOP=1 <<'EOF'
CREATE TEMP TABLE dt_seed (trade_id BIGINT, trade_date DATE);
\copy dt_seed FROM '/tmp/dt_pairs.tsv' WITH (FORMAT text)
INSERT INTO crawl_task (task_type, source, params_json, status, priority, retry_count, max_retry, unique_key, created_at, updated_at)
SELECT 'DRAGON_TIGER_DETAIL', 1,
  '{"tradeId":' || s.trade_id || ',"tradeDate":"' || s.trade_date || '"}',
  'PENDING', 3, 0, 3,
  'DRAGON_TIGER_DETAIL|1|' || s.trade_date || '|' || s.trade_id,
  now(), now()
FROM dt_seed s
WHERE NOT EXISTS (SELECT 1 FROM crawl_task t WHERE t.unique_key = 'DRAGON_TIGER_DETAIL|1|' || s.trade_date || '|' || s.trade_id);
SELECT status, count(*) FROM crawl_task WHERE task_type='DRAGON_TIGER_DETAIL' GROUP BY status;
EOF
