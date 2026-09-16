#!/bin/bash
# ============================================================================
# 远程服务器初始化脚本（在 100.81.7.96 上执行）
# 用途：初始化 ClickHouse + openGauss 表结构
# 执行方式：bash remote_init.sh
# ============================================================================

set -e

CK_HOST="127.0.0.1"
CK_PORT="8123"
CK_USER="default"
CK_PASS="pamirs@123"
CK_DB="crawler"

PG_HOST="127.0.0.1"
PG_PORT="5432"
PG_USER="dbuser"
PG_PASS="OpenGauss@2026"
PG_DB="postgres"

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_DIR="$(dirname "$SCRIPT_DIR")"

echo "============================================"
echo "远程服务器数据库初始化"
echo "============================================"

# ---------------------------------------------------------------------------
# 1. ClickHouse 建库建表
# ---------------------------------------------------------------------------
echo ""
echo "[1/2] ClickHouse 初始化 ..."

# 创建数据库
curl -s "http://${CK_HOST}:${CK_PORT}/" --data "CREATE DATABASE IF NOT EXISTS ${CK_DB}" \
    -u "${CK_USER}:${CK_PASS}" && echo "  数据库 ${CK_DB} 已就绪"

# 执行建表 SQL
if [ -f "${PROJECT_DIR}/clickhouse-schema.sql" ]; then
    echo "  执行 clickhouse-schema.sql ..."
    clickhouse-client --host="${CK_HOST}" --port="${CK_PORT}" \
        --user="${CK_USER}" --password="${CK_PASS}" \
        --database="${CK_DB}" \
        < "${PROJECT_DIR}/clickhouse-schema.sql"
    echo "  ClickHouse 表结构初始化完成"
else
    echo "  [WARN] 未找到 clickhouse-schema.sql，请手动执行"
fi

# ---------------------------------------------------------------------------
# 2. openGauss 建库建表
# ---------------------------------------------------------------------------
echo ""
echo "[2/2] openGauss 初始化 ..."

export PGPASSWORD="${PG_PASS}"

# 执行建表 SQL
if [ -f "${PROJECT_DIR}/schema-full-rebuild.sql" ]; then
    echo "  执行 schema-full-rebuild.sql ..."
    psql -h "${PG_HOST}" -p "${PG_PORT}" -U "${PG_USER}" -d "${PG_DB}" \
        -f "${PROJECT_DIR}/schema-full-rebuild.sql"
    echo "  openGauss 表结构初始化完成"
else
    echo "  [WARN] 未找到 schema-full-rebuild.sql，请手动执行"
fi

unset PGPASSWORD

# ---------------------------------------------------------------------------
# 3. 配置远程访问
# ---------------------------------------------------------------------------
echo ""
echo "[3/3] 配置远程访问 ..."

# ClickHouse: 允许 TailScale 网段连接
CK_CONFIG="/etc/clickhouse-server/config.d/remote_access.xml"
if [ ! -f "${CK_CONFIG}" ]; then
    sudo tee "${CK_CONFIG}" > /dev/null <<'EOF'
<clickhouse>
    <listen_host>0.0.0.0</listen_host>
    <remote_url_allow_hosts>
        <host>100.81.0.0/16</host>
    </remote_url_allow_hosts>
</clickhouse>
EOF
    echo "  ClickHouse 远程访问配置已创建"
    echo "  [ACTION] 请重启 ClickHouse: docker restart clickhouse-server"
else
    echo "  ClickHouse 远程访问配置已存在"
fi

# openGauss: 允许 TailScale 网段连接
PG_HBA="/var/lib/opengauss/data/pg_hba.conf"
if [ -f "${PG_HBA}" ]; then
    if ! grep -q "100.81.0.0/16" "${PG_HBA}"; then
        echo "host    all             all             100.81.0.0/16           md5" | sudo tee -a "${PG_HBA}"
        echo "  openGauss pg_hba.conf 已更新"
        echo "  [ACTION] 请重启 openGauss: docker restart opengauss"
    else
        echo "  openGauss pg_hba.conf 已配置"
    fi
else
    echo "  [WARN] 未找到 pg_hba.conf，请手动配置"
fi

echo ""
echo "============================================"
echo "初始化完成！"
echo "============================================"
echo ""
echo "请确认："
echo "  1. ClickHouse 已重启 (docker restart clickhouse-server)"
echo "  2. openGauss 已重启 (docker restart opengauss)"
echo "  3. 端口可达："
echo "     - CK HTTP: 100.81.7.96:8123"
echo "     - CK Native: 100.81.7.96:9000"
echo "     - PG: 100.81.7.96:5432"
