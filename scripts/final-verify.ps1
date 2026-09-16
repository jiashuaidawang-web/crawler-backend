Write-Host '========================================'
Write-Host '  DATA CONSISTENCY VERIFICATION'
Write-Host '========================================'

# ===== ClickHouse =====
Write-Host ''
Write-Host '--- ClickHouse (Original vs Verify) ---'
Write-Host 'Original tables:'
docker exec clickhouse clickhouse-client --query "SELECT count(*) as cnt FROM system.tables WHERE database = 'crawler'" --password pamirs@123 --format TabSeparated 2>&1
Write-Host 'Verify tables:'
docker exec clickhouse-verify clickhouse-client --query "SELECT count(*) as cnt FROM system.tables WHERE database = 'crawler'" --password pamirs@123 --format TabSeparated 2>&1

Write-Host ''
Write-Host 'Original stock_daily rows:'
docker exec clickhouse clickhouse-client --query "SELECT count(*) FROM crawler.stock_daily" --password pamirs@123 --format TabSeparated 2>&1
Write-Host 'Verify stock_daily rows:'
docker exec clickhouse-verify clickhouse-client --query "SELECT count(*) FROM crawler.stock_daily" --password pamirs@123 --format TabSeparated 2>&1

Write-Host ''
Write-Host 'Original l1_quotes rows:'
docker exec clickhouse clickhouse-client --query "SELECT count(*) FROM crawler.l1_quotes" --password pamirs@123 --format TabSeparated 2>&1
Write-Host 'Verify l1_quotes rows:'
docker exec clickhouse-verify clickhouse-client --query "SELECT count(*) FROM crawler.l1_quotes" --password pamirs@123 --format TabSeparated 2>&1

# ===== openGauss =====
Write-Host ''
Write-Host '--- openGauss (Original vs Verify) ---'

# Original
$sqldir = Join-Path $env:TEMP 'verify_sql'
New-Item -ItemType Directory -Force -Path $sqldir | Out-Null

'select count(*) from pg_tables where schemaname = ''public'';' | Set-Content "$sqldir\orig_count.sql" -Encoding utf8
docker cp "$sqldir\orig_count.sql" opengauss-lite:/tmp/orig_count.sql
Write-Host 'Original public tables count:'
docker exec -u omm -e LD_LIBRARY_PATH=/usr/local/opengauss/lib opengauss-lite bash -c '/usr/local/opengauss/bin/gsql -d postgres -p 5432 -f /tmp/orig_count.sql' 2>&1

'select count(*) from crawl_task;' | Set-Content "$sqldir\orig_task.sql" -Encoding utf8
docker cp "$sqldir\orig_task.sql" opengauss-lite:/tmp/orig_task.sql
Write-Host 'Original crawl_task rows:'
docker exec -u omm -e LD_LIBRARY_PATH=/usr/local/opengauss/lib opengauss-lite bash -c '/usr/local/opengauss/bin/gsql -d postgres -p 5432 -f /tmp/orig_task.sql' 2>&1

# Verify
'select count(*) from pg_tables where schemaname = ''public'';' | Set-Content "$sqldir\ver_count.sql" -Encoding utf8
docker cp "$sqldir\ver_count.sql" opengauss-verify:/tmp/ver_count.sql
Write-Host 'Verify public tables count:'
docker exec -u omm -e LD_LIBRARY_PATH=/usr/local/opengauss/lib opengauss-verify bash -c '/usr/local/opengauss/bin/gsql -d postgres -p 5432 -f /tmp/ver_count.sql' 2>&1

'select count(*) from crawl_task;' | Set-Content "$sqldir\ver_task.sql" -Encoding utf8
docker cp "$sqldir\ver_task.sql" opengauss-verify:/tmp/ver_task.sql
Write-Host 'Verify crawl_task rows:'
docker exec -u omm -e LD_LIBRARY_PATH=/usr/local/opengauss/lib opengauss-verify bash -c '/usr/local/opengauss/bin/gsql -d postgres -p 5432 -f /tmp/ver_task.sql' 2>&1

'select count(*) from trade_log;' | Set-Content "$sqldir\ver_trade.sql" -Encoding utf8
docker cp "$sqldir\ver_trade.sql" opengauss-verify:/tmp/ver_trade.sql
Write-Host 'Verify trade_log rows:'
docker exec -u omm -e LD_LIBRARY_PATH=/usr/local/opengauss/lib opengauss-verify bash -c '/usr/local/opengauss/bin/gsql -d postgres -p 5432 -f /tmp/ver_trade.sql' 2>&1

'select count(*) from trade_log;' | Set-Content "$sqldir\orig_trade.sql" -Encoding utf8
docker cp "$sqldir\orig_trade.sql" opengauss-lite:/tmp/orig_trade.sql
Write-Host 'Original trade_log rows:'
docker exec -u omm -e LD_LIBRARY_PATH=/usr/local/opengauss/lib opengauss-lite bash -c '/usr/local/opengauss/bin/gsql -d postgres -p 5432 -f /tmp/orig_trade.sql' 2>&1

Write-Host ''
Write-Host '========================================'
Write-Host '  VERIFICATION COMPLETE'
Write-Host '========================================'
