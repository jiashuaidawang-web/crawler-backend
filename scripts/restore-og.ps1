Write-Host '=== Re-restoring openGauss dump ==='
docker cp 'D:\backup\crawler\2026-09-09\opengauss.dump' opengauss-verify:/tmp/opengauss.dump
# Fix permissions so omm can read it
docker exec opengauss-verify bash -c 'chmod 644 /tmp/opengauss.dump && chown omm:omm /tmp/opengauss.dump'
Write-Host 'File copied. Running gs_restore...'
docker exec -u omm -e LD_LIBRARY_PATH=/usr/local/opengauss/lib opengauss-verify bash -c '/usr/local/opengauss/bin/gs_restore -d postgres -p 5432 /tmp/opengauss.dump --verbose 2>&1'
Write-Host "Exit code: $LASTEXITCODE"
docker exec opengauss-verify rm -f /tmp/opengauss.dump

# Check tables after restore
Write-Host ''
Write-Host '=== Tables after restore ==='
'select schemaname, tablename from pg_tables where schemaname = ''public'' order by 2;' | Set-Content 'C:\Users\ADMINI~1\AppData\Local\Temp\check.sql' -Encoding utf8
docker cp 'C:\Users\ADMINI~1\AppData\Local\Temp\check.sql' opengauss-verify:/tmp/check.sql
docker exec -u omm -e LD_LIBRARY_PATH=/usr/local/opengauss/lib opengauss-verify bash -c '/usr/local/opengauss/bin/gsql -d postgres -p 5432 -f /tmp/check.sql' 2>&1
