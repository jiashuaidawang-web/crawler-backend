# 从某天的备份目录恢复。先停容器，再解压/导入。
#   .\restore-crawler.ps1 -Day 2026-08-18
# 备份可来自本机 D:\backup\crawler\ 或从 Grok Bot /workspace/crawler-backup/ 拷回来的目录。

param(
    [Parameter(Mandatory = $true)][string]$Day,
    [string]$InRoot = 'D:\backup\crawler',
    [string]$CkVolume = 'docker-deploy_ck-data',
    [string]$CkContainer = 'clickhouse',
    [string]$OgContainer = 'opengauss-lite',
    [string]$OgUser = 'dbuser',
    [string]$OgDb = 'postgres'
)

$ErrorActionPreference = 'Stop'
$src = Join-Path $InRoot $Day
$ck = Join-Path $src 'clickhouse.tgz'
$og = Join-Path $src 'opengauss.dump'
if (-not (Test-Path $ck)) { throw "找不到 $ck" }
if (-not (Test-Path $og)) { throw "找不到 $og" }

Write-Host "将用 $src 覆盖当前 CK volume 和 openGauss 库。确认已停业务。"
$confirm = Read-Host '输入 YES 继续'
if ($confirm -ne 'YES') { throw '已取消' }

Write-Host '[CK] 停止 clickhouse 并覆盖 volume ...'
docker stop $CkContainer
docker run --rm `
    -v "${CkVolume}:/data" `
    -v "${src}:/backup" `
    alpine:3.20 `
    sh -c 'rm -rf /data/* /data/.[!.]* ; tar xzf /backup/clickhouse.tgz -C /data'
if ($LASTEXITCODE -ne 0) { throw 'CK 解压失败' }
docker start $CkContainer
Write-Host '[CK] 已启动，等十几秒再查 8123'

$pwd = $env:OG_PASSWORD
if ([string]::IsNullOrWhiteSpace($pwd)) {
    throw '未设置 OG_PASSWORD'
}
docker cp $og "${OgContainer}:/tmp/opengauss.dump"
$restoreCmd = @"
set -e
export PGPASSWORD='$pwd'
if command -v gs_restore >/dev/null 2>&1; then
  gs_restore -U $OgUser -d $OgDb -p 5432 -c /tmp/opengauss.dump || gs_restore -U $OgUser -d $OgDb -p 5432 /tmp/opengauss.dump
elif [ -f /usr/local/opengauss/bin/gs_restore ]; then
  /usr/local/opengauss/bin/gs_restore -U $OgUser -d $OgDb -p 5432 -c /tmp/opengauss.dump || true
  /usr/local/opengauss/bin/gs_restore -U $OgUser -d $OgDb -p 5432 /tmp/opengauss.dump
else
  su - omm -c "export PGPASSWORD='$pwd'; gs_restore -U $OgUser -d $OgDb -p 5432 /tmp/opengauss.dump"
fi
rm -f /tmp/opengauss.dump
"@
Write-Host '[OG] restore dump ...'
docker exec -e PGPASSWORD=$pwd $OgContainer bash -lc $restoreCmd
if ($LASTEXITCODE -ne 0) { throw 'openGauss 恢复失败' }
Write-Host "恢复完成: $Day"
