#!/bin/bash
# 对 stock_daily part 数 >50 的分区逐个 OPTIMIZE（强制合并+去重），收敛 part 总数
export MSYS_NO_PATHCONV=1
CK="http://localhost:8123/"; AUTH="default:pamirs@123"
LOG="D:/Development/IDEAWorkSpace/Github/new/crawler-backend/scripts/backfill/optimize_progress.log"
echo "start $(date '+%H:%M:%S')" > "$LOG"
PARTS=$(curl -s --max-time 60 --user "$AUTH" --data-binary "SELECT partition FROM system.parts WHERE database='crawler' AND table='stock_daily' AND active GROUP BY partition HAVING count() > 50 ORDER BY count() DESC FORMAT TSV" "$CK")
TOTAL=$(echo "$PARTS" | wc -l)
N=0
for P in $PARTS; do
  N=$((N+1))
  if curl -s --max-time 600 --user "$AUTH" --data-binary "OPTIMIZE TABLE crawler.stock_daily PARTITION $P" "$CK"; then
    echo "$(date '+%H:%M:%S') [$N/$TOTAL] OK $P" >> "$LOG"
  else
    echo "$(date '+%H:%M:%S') [$N/$TOTAL] FAIL $P" >> "$LOG"
  fi
done
echo "done $(date '+%H:%M:%S')" >> "$LOG"
