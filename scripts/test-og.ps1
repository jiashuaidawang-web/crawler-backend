# Connect via local socket (trust auth) as omm
Write-Host "=== Local socket connection as omm ==="
docker exec -u omm -e LD_LIBRARY_PATH=/usr/local/opengauss/lib opengauss-lite bash -c '/usr/local/opengauss/bin/gsql -d postgres -p 5432 -c "SELECT 1" 2>&1'
Write-Host "Exit code: $LASTEXITCODE"

# Try gs_dump via local socket (no password needed)
Write-Host "`n=== gs_dump via local socket ==="
docker exec -u omm -e LD_LIBRARY_PATH=/usr/local/opengauss/lib opengauss-lite bash -c '/usr/local/opengauss/bin/gs_dump -p 5432 -F c -f /tmp/opengauss.dump postgres 2>&1'
Write-Host "Exit code: $LASTEXITCODE"
docker exec opengauss-lite bash -c "ls -la /tmp/opengauss.dump 2>&1"