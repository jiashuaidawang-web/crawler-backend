# Backup ClickHouse + openGauss to daily directory
param(
    [string]$OutRoot = 'D:\backup\crawler',
    [int]$KeepDays = 14,
    [string]$CkContainer = 'clickhouse',
    [string]$OgContainer = 'opengauss-lite',
    [string]$OgDb = 'postgres'
)

$ErrorActionPreference = 'Stop'
$day = Get-Date -Format 'yyyy-MM-dd'
$dest = Join-Path $OutRoot $day
New-Item -ItemType Directory -Force -Path $dest | Out-Null

function Assert-Docker {
    docker info 1>$null 2>$null
    if ($LASTEXITCODE -ne 0) { throw 'docker not available, start Docker Desktop first' }
}

function Invoke-CkBackup {
    Write-Host '[CK] Packing via stdout -> host...'
    $tarName = 'clickhouse.tgz'
    $outFile = Join-Path $dest $tarName
    docker exec $CkContainer tar cf - --warning=no-file-changed --warning=no-file-removed -C /var/lib/clickhouse . | gzip -c1 > $outFile
    if ($LASTEXITCODE -ne 0) { throw 'ClickHouse pack failed (exit code ' + $LASTEXITCODE + ')' }
    $size = (Get-Item $outFile).Length
    Write-Host ('[CK] Done ' + $tarName + ' (' + [math]::Round($size/1MB, 1) + ' MB)')
}

function Invoke-OgBackup {
    $dumpIn = '/tmp/opengauss.dump'
    $dumpOut = Join-Path $dest 'opengauss.dump'
    Write-Host ('[OG] dump ' + $OgDb + ' @' + $OgContainer + ' ...')

    # Use local socket with trust auth (no password needed)
    docker exec -u omm -e LD_LIBRARY_PATH=/usr/local/opengauss/lib $OgContainer bash -c ('/usr/local/opengauss/bin/gs_dump -p 5432 -F c -f ' + $dumpIn + ' ' + $OgDb)
    if ($LASTEXITCODE -ne 0) { throw 'openGauss dump failed' }
    docker cp ($OgContainer + ':' + $dumpIn) $dumpOut
    docker exec $OgContainer rm -f $dumpIn
    $size = (Get-Item $dumpOut).Length
    Write-Host ('[OG] Done opengauss.dump (' + [math]::Round($size/1MB, 1) + ' MB)')
}

function Remove-OldLocal {
    Get-ChildItem $OutRoot -Directory -ErrorAction SilentlyContinue |
        Where-Object { $_.Name -match '^\d{4}-\d{2}-\d{2}$' } |
        Where-Object { $_.LastWriteTime -lt (Get-Date).AddDays(-$KeepDays) } |
        ForEach-Object {
            Write-Host ('[retain] Removing ' + $_.FullName)
            Remove-Item $_.FullName -Recurse -Force
        }
}

Assert-Docker
if (-not (docker ps --format '{{.Names}}' | Select-String -SimpleMatch $CkContainer)) {
    throw ('Container ' + $CkContainer + ' not running')
}
if (-not (docker ps --format '{{.Names}}' | Select-String -SimpleMatch $OgContainer)) {
    throw ('Container ' + $OgContainer + ' not running')
}

Invoke-CkBackup
Invoke-OgBackup

$ok = Join-Path $dest 'DONE.txt'
$doneContent = 'date=' + $day + "`n" +
    'clickhouse=clickhouse.tgz' + "`n" +
    'opengauss=opengauss.dump' + "`n" +
    'host=' + $env:COMPUTERNAME + "`n"
$doneContent | Set-Content -Path $ok -Encoding utf8

Remove-OldLocal
Write-Host ('Backup complete: ' + $dest)
