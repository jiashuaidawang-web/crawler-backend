$sqldir = Join-Path $env:TEMP 'og_queries'
New-Item -ItemType Directory -Force -Path $sqldir | Out-Null

# Query 1: List databases
'\l+' | Set-Content "$sqldir\q1.sql" -Encoding utf8
docker cp "$sqldir\q1.sql" opengauss-lite:/tmp/q1.sql
Write-Host '=== Original openGauss - Databases ==='
docker exec -u omm -e LD_LIBRARY_PATH=/usr/local/opengauss/lib opengauss-lite bash -c '/usr/local/opengauss/bin/gsql -d postgres -p 5432 -f /tmp/q1.sql' 2>&1

# Query 2: Table counts by schema
'select schemaname, count(*) from pg_tables group by schemaname order by 2 desc;' | Set-Content "$sqldir\q2.sql" -Encoding utf8
docker cp "$sqldir\q2.sql" opengauss-lite:/tmp/q2.sql
Write-Host ''
Write-Host '=== Original openGauss - Tables by schema ==='
docker exec -u omm -e LD_LIBRARY_PATH=/usr/local/opengauss/lib opengauss-lite bash -c '/usr/local/opengauss/bin/gsql -d postgres -p 5432 -f /tmp/q2.sql' 2>&1

# Query 3: Business tables
'select schemaname, tablename from pg_tables where schemaname not in (''pg_catalog'',''information_schema'',''pg_toast'',''cstore'') order by 1, 2;' | Set-Content "$sqldir\q3.sql" -Encoding utf8
docker cp "$sqldir\q3.sql" opengauss-lite:/tmp/q3.sql
Write-Host ''
Write-Host '=== Original openGauss - Business tables ==='
docker exec -u omm -e LD_LIBRARY_PATH=/usr/local/opengauss/lib opengauss-lite bash -c '/usr/local/opengauss/bin/gsql -d postgres -p 5432 -f /tmp/q3.sql' 2>&1
