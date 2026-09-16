# Restore backup to new containers on different ports for verification
$ErrorActionPreference = 'Stop'
$backupDir = 'D:\backup\crawler\2026-09-09'

Write-Host '=== Creating verification containers ==='

# Create volumes
docker volume create verify_ck_data 2>&1 | Out-Null
docker volume create verify_og_data 2>&1 | Out-Null

# ===== ClickHouse =====
Write-Host '`n[CK] Extracting clickhouse.tgz into volume ...'
# Use ubuntu (or any available image) to extract tar.gz directly into volume
# Copy backup to a path the container can access
$ckBackup = Join-Path $backupDir 'clickhouse.tgz'
docker run --rm `
    -v "verify_ck_data:/data" `
    -v "${backupDir}:/backup:ro" `
    alpine:latest `
    sh -c 'tar xzf /backup/clickhouse.tgz -C /data'
if ($LASTEXITCODE -ne 0) { throw 'ClickHouse extraction failed' }
Write-Host '[CK] Data extracted to volume'

# Start new ClickHouse on different ports (8124, 9001)
docker run -d `
    --name clickhouse-verify `
    -v "verify_ck_data:/var/lib/clickhouse" `
    -e CLICKHOUSE_PASSWORD='pamirs@123' `
    -e CLICKHOUSE_DO_NOT_CHOWN=1 `
    -p 8124:8123 `
    -p 9001:9000 `
    clickhouse/clickhouse-server:23.8

Write-Host '[CK] Started clickhouse-verify on ports 8124/9001'

# ===== openGauss =====
Write-Host '`n[OG] Starting fresh openGauss for restore ...'
$ogDataDir = 'D:\Development\Docker\verify-opengauss-data'
New-Item -ItemType Directory -Force -Path $ogDataDir | Out-Null

# Start a fresh openGauss container on port 5433
docker run -d `
    --name opengauss-verify `
    -v "${ogDataDir}:/var/lib/opengauss/data" `
    -e GS_PASSWORD='OpenGauss@2026' `
    -p 5433:5432 `
    enmotech/opengauss-lite:latest

Write-Host '[OG] Started opengauss-verify on port 5433'
Write-Host '[OG] Waiting for openGauss to be ready...'

# Wait for openGauss to be ready
$maxWait = 180
$waited = 0
$ready = $false
while ($waited -lt $maxWait) {
    Start-Sleep -Seconds 10
    $waited += 10
    $result = docker exec -u omm -e LD_LIBRARY_PATH=/usr/local/opengauss/lib opengauss-verify bash -c '/usr/local/opengauss/bin/gsql -d postgres -p 5432 -c "SELECT 1"' 2>&1 | Out-String
    if ($result -match '1 row') {
        $ready = $true
        Write-Host "[OG] Ready after ${waited}s"
        break
    }
    Write-Host "[OG] Waiting... (${waited}s / ${maxWait}s)"
}

if (-not $ready) { throw 'openGauss failed to start' }

# Restore the dump
Write-Host '[OG] Restoring dump ...'
docker cp (Join-Path $backupDir 'opengauss.dump') opengauss-verify:/tmp/opengauss.dump
docker exec -u omm -e LD_LIBRARY_PATH=/usr/local/opengauss/lib opengauss-verify bash -c '/usr/local/opengauss/bin/gs_restore -d postgres -p 5432 /tmp/opengauss.dump 2>&1'
docker exec opengauss-verify rm -f /tmp/opengauss.dump
Write-Host '[OG] Restore complete'

Write-Host '`n=== Verification Containers Ready ==='
Write-Host 'ClickHouse: localhost:8124 (HTTP), localhost:9001 (Native)'
Write-Host 'openGauss: localhost:5433'
Write-Host 'Password: pamirs@123 (CK), OpenGauss@2026 (OG)'
