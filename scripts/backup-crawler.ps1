# 把本机 ClickHouse + openGauss 打成当天目录，供 Grok Bot 拷到 /workspace/crawler-backup/
# 用法（PowerShell）:
#   $env:OG_PASSWORD = '你的库密码'
#   .\backup-crawler.ps1
# 可选: -OutRoot E:\backup\crawler

param(
    [string]$OutRoot = 'D:\backup\crawler',
    [int]$KeepDays = 14,
    [string]$CkVolume = 'docker-deploy_ck-data',
    [string]$CkContainer = 'clickhouse',
    [string]$OgContainer = 'opengauss-lite',
    [string]$OgUser = 'dbuser',
    [string]$OgDb = 'postgres'
)

$ErrorActionPreference = 'Stop'
$day = Get-Date -Format 'yyyy-MM-dd'
$dest = Join-Path $OutRoot $day
New-Item -ItemType Directory -Force -Path $dest | Out-Null

function Assert-Docker {
    docker info 1>$null 2>$null
    if ($LASTEXITCODE -ne 0) { throw 'docker 不可用，先开 Docker Desktop' }
}

function Invoke-CkBackup {
    Write-Host "[CK] 打包 volume $CkVolume ..."
    $tarName = 'clickhouse.tgz'
    docker run --rm `
        -v "${CkVolume}:/data:ro" `
        -v "${dest}:/backup" `
        alpine:3.20 `
        tar czf "/backup/$tarName" -C /data .
    if ($LASTEXITCODE -ne 0) { throw "ClickHouse 打包失败，确认容器 $CkContainer 和 volume $CkVolume 存在" }
    $size = (Get-Item (Join-Path $dest $tarName)).Length
    Write-Host "[CK] 完成 $tarName ($([math]::Round($size/1MB, 1)) MB)"
}

function Invoke-OgBackup {
    $pwd = $env:OG_PASSWORD
    if ([string]::IsNullOrWhiteSpace($pwd)) {
        throw '未设置 OG_PASSWORD。先执行: $env:OG_PASSWORD = ''你的openGauss密码'''
    }
    $dumpIn = '/tmp/opengauss.dump'
    $dumpOut = Join-Path $dest 'opengauss.dump'
    Write-Host "[OG] dump $OgDb @$OgContainer ..."

    $dumpCmd = @"
set -e
export PGPASSWORD='$pwd'
if command -v gs_dump >/dev/null 2>&1; then
  gs_dump -U $OgUser -d $OgDb -p 5432 -F c -f $dumpIn
elif [ -f /usr/local/opengauss/bin/gs_dump ]; then
  /usr/local/opengauss/bin/gs_dump -U $OgUser -d $OgDb -p 5432 -F c -f $dumpIn
else
  su - omm -c "export PGPASSWORD='$pwd'; gs_dump -U $OgUser -d $OgDb -p 5432 -F c -f $dumpIn"
fi
"@
    docker exec -e PGPASSWORD=$pwd $OgContainer bash -lc $dumpCmd
    if ($LASTEXITCODE -ne 0) { throw 'openGauss dump 失败，检查 OG_PASSWORD / 用户 dbuser' }
    docker cp "${OgContainer}:${dumpIn}" $dumpOut
    docker exec $OgContainer rm -f $dumpIn
    $size = (Get-Item $dumpOut).Length
    Write-Host "[OG] 完成 opengauss.dump ($([math]::Round($size/1MB, 1)) MB)"
}

function Remove-OldLocal {
    Get-ChildItem $OutRoot -Directory -ErrorAction SilentlyContinue |
        Where-Object { $_.Name -match '^\d{4}-\d{2}-\d{2}$' } |
        Where-Object { $_.LastWriteTime -lt (Get-Date).AddDays(-$KeepDays) } |
        ForEach-Object {
            Write-Host "[retain] 删除本地过期 $($_.FullName)"
            Remove-Item $_.FullName -Recurse -Force
        }
}

Assert-Docker
if (-not (docker ps --format '{{.Names}}' | Select-String -SimpleMatch $CkContainer)) {
    throw "容器 $CkContainer 未在运行"
}
if (-not (docker ps --format '{{.Names}}' | Select-String -SimpleMatch $OgContainer)) {
    throw "容器 $OgContainer 未在运行"
}

Invoke-CkBackup
Invoke-OgBackup

$ok = Join-Path $dest 'DONE.txt'
@"
date=$day
clickhouse=clickhouse.tgz
opengauss=opengauss.dump
host=$env:COMPUTERNAME
"@ | Set-Content -Path $ok -Encoding utf8

Remove-OldLocal
Write-Host "备份完成: $dest"
Write-Host "等 Grok Bot 把该目录拷到 /workspace/crawler-backup/$day/"
