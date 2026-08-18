#!/usr/bin/env bash
# 青果代理更换后 每分钟监控脚本
# 每分钟扫描 info.log / error.log 的增量行,输出两应用(Worker/Admin)的执行状态/异常/成功率
# 用行号增量(而非时间戳)避免跨分钟边界漏计
LOG_INFO="/Users/edy/IdeaProjects/stock/craw/crawler-backend/logs/info.log"
LOG_ERROR="/Users/edy/IdeaProjects/stock/craw/crawler-backend/logs/error.log"
REPORT="/Users/edy/IdeaProjects/stock/craw/crawler-backend/monitoring/report.log"
STATE="/Users/edy/IdeaProjects/stock/craw/crawler-backend/monitoring/.lastline"

# 起始行号 = 当前末尾,之后只看增量
last_info=$(wc -l < "$LOG_INFO" 2>/dev/null || echo 0)
last_error=$(wc -l < "$LOG_ERROR" 2>/dev/null || echo 0)
echo "$last_info" > "$STATE"
echo "$last_error" >> "$STATE"

echo "===== 监控启动 $(date '+%Y-%m-%d %H:%M:%S') =====" >> "$REPORT"
echo "  起始 info.log 行=$last_info error.log 行=$last_error" >> "$REPORT"

while true; do
  sleep 60

  cur_info=$(wc -l < "$LOG_INFO" 2>/dev/null || echo 0)
  cur_error=$(wc -l < "$LOG_ERROR" 2>/dev/null || echo 0)
  from_info=$((last_info + 1))
  from_error=$((last_error + 1))

  # 提取增量行(用 sed 行号范围);若文件被轮转(行数变小),全量重扫
  if [ "$cur_info" -ge "$from_info" ]; then
    info_delta=$(sed -n "${from_info},${cur_info}p" "$LOG_INFO" 2>/dev/null)
  else
    info_delta=$(cat "$LOG_INFO" 2>/dev/null)
  fi
  if [ "$cur_error" -ge "$from_error" ]; then
    error_delta=$(sed -n "${from_error},${cur_error}p" "$LOG_ERROR" 2>/dev/null)
  else
    error_delta=$(cat "$LOG_ERROR" 2>/dev/null)
  fi

  # Worker 统计
  W_COMPLETE=$(printf '%s\n' "$info_delta" | grep -c 'ClaimLoop : \[ complete ok' || true)
  W_COMPLETE_FAIL=$(printf '%s\n' "$info_delta" | grep 'ClaimLoop : \[ complete' | grep -cv 'complete ok' || true)
  W_FETCH_OK=$(printf '%s\n' "$info_delta" | grep -c 'fetchWithWorkerProxy.*success' || true)
  W_FETCH_FAIL=$(printf '%s\n' "$info_delta" | grep -c 'fetchWithWorkerProxy.*proxy failed' || true)
  W_PROXY_NEW=$(printf '%s\n' "$info_delta" | grep -c 'WorkerProxyManager : \[WorkerProxyManager\] new proxy acquired' || true)
  W_PROXY_MARK=$(printf '%s\n' "$info_delta" | grep -c 'WorkerProxyManager.*proxy marked invalid' || true)
  W_TASKS=$((W_COMPLETE + W_COMPLETE_FAIL))
  if [ "$W_TASKS" -gt 0 ]; then
    W_RATE=$(( (W_COMPLETE * 100) / W_TASKS ))
  else
    W_RATE="N/A"
  fi

  # Admin 统计(包名前缀 c.d.crawler.admin)
  A_COMPLETE=$(printf '%s\n' "$info_delta" | grep 'c\.d\.crawler\.admin' | grep -c 'complete ok' || true)
  A_FETCH_OK=$(printf '%s\n' "$info_delta" | grep 'c\.d\.crawler\.admin' | grep -c 'fetchWithWorkerProxy.*success' || true)
  A_PROXY_NEW=$(printf '%s\n' "$info_delta" | grep 'c\.d\.crawler\.admin' | grep -c 'new proxy acquired' || true)

  # 异常: error.log 增量中的 ERROR/Exception 行(去栈帧)
  ERR_COUNT=$(printf '%s\n' "$error_delta" | grep -cE 'ERROR|Exception' || true)
  ERR_SNIPPET=$(printf '%s\n' "$error_delta" | grep -E ' ERROR | Exception' | grep -vE '^\s+at |common frames omitted' | tail -3 | sed 's/^/    /')

  last_info=$cur_info
  last_error=$cur_error
  echo "$last_info" > "$STATE"
  echo "$last_error" >> "$STATE"

  NOW=$(date '+%Y-%m-%d %H:%M:%S')
  {
    echo "[$NOW] minute report"
    echo "  [Worker] done=$W_COMPLETE fail=$W_COMPLETE_FAIL rate=${W_RATE}% | fetch ok=$W_FETCH_OK fail=$W_FETCH_FAIL | newIP=$W_PROXY_NEW invalid=$W_PROXY_MARK"
    echo "  [Admin]  done=$A_COMPLETE | fetch ok=$A_FETCH_OK | newIP=$A_PROXY_NEW"
    echo "  errors=$ERR_COUNT"
    if [ -n "$ERR_SNIPPET" ]; then echo "  error snippets:"; echo "$ERR_SNIPPET"; fi
    echo "--------------------------------------------------"
  } >> "$REPORT"

  tail -8 "$REPORT"
done
