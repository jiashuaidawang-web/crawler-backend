$backupRoot = "E:\Development\Docker\backups"
$timestamp = Get-Date -Format "yyyyMMdd_HHmmss"
$backupDir = "$backupRoot\$timestamp"
New-Item -ItemType Directory -Path $backupDir -Force | Out-Null
New-Item -ItemType Directory -Path "$backupDir\clickhouse-data" -Force | Out-Null
New-Item -ItemType Directory -Path "$backupDir\opengauss-data" -Force | Out-Null
New-Item -ItemType Directory -Path "$backupDir\redis-data" -Force | Out-Null
Write-Host "Backup dir: $backupDir"

Write-Host "Backing up ClickHouse..."
docker cp clickhouse:/var/lib/clickhouse/. "$backupDir\clickhouse-data\"
$ckSize = (Get-ChildItem "$backupDir\clickhouse-data" -Recurse -File -ErrorAction SilentlyContinue | Measure-Object -Property Length -Sum).Sum
Write-Host "ClickHouse done: $([math]::Round($ckSize/1GB,2)) GB"

Write-Host "Backing up OpenGauss..."
docker cp opengauss-lite:/var/lib/opengauss/data/. "$backupDir\opengauss-data\"
$ogSize = (Get-ChildItem "$backupDir\opengauss-data" -Recurse -File -ErrorAction SilentlyContinue | Measure-Object -Property Length -Sum).Sum
Write-Host "OpenGauss done: $([math]::Round($ogSize/1GB,2)) GB"

Write-Host "Backing up Redis..."
docker cp redis-l2:/data/. "$backupDir\redis-data\"
$rdSize = (Get-ChildItem "$backupDir\redis-data" -Recurse -File -ErrorAction SilentlyContinue | Measure-Object -Property Length -Sum).Sum
Write-Host "Redis done: $([math]::Round($rdSize/1MB,2)) MB"

Write-Host "=== Backup complete ==="
Write-Host "Location: $backupDir"
