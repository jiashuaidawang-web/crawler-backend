#!/bin/bash
# 通过青果代理拉取指数全历史K线日期（参数：secid 输出文件）
SECID="$1"; OUT="$2"
KEY="NM8XFDR2"; PASS="22574E11640D"
UA="Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36"
PROXY=""
acquire() {
  local RESP=$(curl -s --max-time 20 "https://share.proxy.qg.net/get?key=${KEY}&num=1&area=&isp=0&format=json&distinct=true")
  local SERVER=$(echo "$RESP" | grep -o '"server":"[^"]*"' | head -1 | sed 's/"server":"//;s/"//')
  if [ -n "$SERVER" ]; then PROXY="http://${KEY}:${PASS}@${SERVER}"; echo "acquired: ${SERVER}"; else echo "acquire FAILED"; return 1; fi
}
> "${OUT}.tmp"
FAIL_TOTAL=0
for Y in $(seq 1990 2026); do
  URL="https://push2his.eastmoney.com/api/qt/stock/kline/get?secid=${SECID}&klt=101&fqt=0&beg=${Y}0101&end=${Y}1231&lmt=400&fields1=f1,f2&fields2=f51"
  OK=0
  for TRY in 1 2 3 4 5; do
    if [ -z "$PROXY" ]; then acquire || { sleep 3; continue; }; fi
    curl -s --max-time 40 -x "$PROXY" -H "User-Agent: $UA" "$URL" -o "${OUT}.part" 2>/dev/null
    if [ -s "${OUT}.part" ] && grep -q '"klines"' "${OUT}.part"; then OK=1; break; fi
    PROXY=""; sleep 2
  done
  if [ $OK -eq 1 ]; then grep -oE '[0-9]{4}-[0-9]{2}-[0-9]{2}' "${OUT}.part" >> "${OUT}.tmp"; else echo "year $Y GAVE UP"; FAIL_TOTAL=$((FAIL_TOTAL+1)); fi
  sleep 0.2
done
sort -u "${OUT}.tmp" > "$OUT" && rm -f "${OUT}.tmp" "${OUT}.part"
echo "DONE secid=$SECID failed=$FAIL_TOTAL dates=$(wc -l < "$OUT") first=$(head -1 "$OUT") last=$(tail -1 "$OUT")"
