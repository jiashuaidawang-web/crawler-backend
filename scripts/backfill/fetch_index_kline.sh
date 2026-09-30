#!/bin/bash
# 通过青果代理拉取上证指数全历史K线日期（权威交易日历）
OUT="D:/Development/IDEAWorkSpace/Github/new/crawler-backend/scripts/backfill/index_kline_dates.json"
KEY="NM8XFDR2"; PASS="22574E11640D"
UA="Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36"

PROXY=""
acquire() {
  local RESP=$(curl -s --max-time 20 "https://share.proxy.qg.net/get?key=${KEY}&num=1&area=&isp=0&format=json&distinct=true")
  local SERVER=$(echo "$RESP" | grep -o '"server":"[^"]*"' | head -1 | sed 's/"server":"//;s/"//')
  if [ -n "$SERVER" ]; then PROXY="http://${KEY}:${PASS}@${SERVER}"; echo "acquired: ${SERVER}"; else echo "acquire FAILED: $RESP"; return 1; fi
}

fetch_url() { # $1=url, $2=output-file
  curl -s --max-time 40 -x "$PROXY" -H "User-Agent: $UA" "$1" -o "$2"
}

# 主循环：按年拉，失败换IP重试
> "${OUT}.tmp"
FAIL_TOTAL=0
for Y in $(seq 1990 2026); do
  URL="https://push2his.eastmoney.com/api/qt/stock/kline/get?secid=1.000001&klt=101&fqt=0&beg=${Y}0101&end=${Y}1231&lmt=400&fields1=f1,f2&fields2=f51"
  OK=0
  for TRY in 1 2 3 4 5; do
    if [ -z "$PROXY" ]; then acquire || { sleep 3; continue; }; fi
    fetch_url "$URL" "${OUT}.part" 2>/dev/null
    if [ -s "${OUT}.part" ] && grep -q '"klines"' "${OUT}.part"; then OK=1; break; fi
    echo "year $Y try $TRY failed (exit $?), rotating IP..."
    PROXY=""; sleep 2
  done
  if [ $OK -eq 1 ]; then
    grep -oE '[0-9]{4}-[0-9]{2}-[0-9]{2}' "${OUT}.part" >> "${OUT}.tmp"
  else
    echo "year $Y GAVE UP"
    FAIL_TOTAL=$((FAIL_TOTAL+1))
  fi
  sleep 0.2
done
sort -u "${OUT}.tmp" > "$OUT" && rm -f "${OUT}.tmp" "${OUT}.part"
DATES=$(wc -l < "$OUT")
echo "DONE: failed_years=$FAIL_TOTAL, unique_dates=$DATES"
echo "first: $(head -1 "$OUT")  last: $(tail -1 "$OUT")"
