#!/bin/bash
# 并行 OPTIMIZE stock_daily 热分区（8路并发），收敛 part 总数
export MSYS_NO_PATHCONV=1
CK="http://localhost:8123/"; AUTH="default:pamirs@123"
LOG="D:/Development/IDEAWorkSpace/Github/new/crawler-backend/scripts/backfill/optimize_progress.log"
echo "parallel start $(date '+%H:%M:%S')" >> "$LOG"
PARTS=$(curl -s --max-time 60 --user "$AUTH" --data-binary "SELECT partition FROM system.parts WHERE database='crawler' AND table='stock_daily' AND active GROUP BY partition HAVING count() > 50 ORDER BY count() DESC FORMAT TSV" "$CK")
TOTAL=$(echo "$PARTS" | wc -l)
echo "targets=$TOTAL" >> "$LOG"
N=0
for P in $PARTS; do
  N=$((N+1))
  (
    if curl -s --max-time 900 --user "$AUTH" --data-binary "OPTIMIZE TABLE crawler.stock_daily PARTITION $P" "$CK"; then
      echo "$(date '+%H:%M:%S') [$N/$TOTAL] OK $P" >> "$LOG"
    else
      echo "$(date '+%H:%M:%S') [$N/$TOTAL] FAIL $P" >> "$LOG"
    fi
  ) &
  # 8 路并发
  if [ $((N % 8)) -eq 0 ]; then wait; fi
done
wait
echo "parallel done $(date '+%H:%M:%S')" >> "$LOG"
