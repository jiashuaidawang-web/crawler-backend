#!/usr/bin/env python3
"""初始化远程数据库表结构（ClickHouse + openGauss）"""
import paramiko
import time
import base64
import os

def main():
    ssh = paramiko.SSHClient()
    ssh.set_missing_host_key_policy(paramiko.AutoAddPolicy())
    ssh.connect('100.81.7.96', 22, 'box', '3Uz9wVIZ2mpPDoPG', timeout=15, allow_agent=False, look_for_keys=False)

    def run(cmd, timeout=120):
        stdin, stdout, stderr = ssh.exec_command(cmd, timeout=timeout)
        out = stdout.read().decode().strip()
        err = stderr.read().decode().strip()
        return out, err

    gaussdb_pwd = base64.b64encode("GudaoBackup_2026!".encode()).decode()

    # ========================================================================
    # 1. ClickHouse: 执行 clickhouse-schema.sql
    # ========================================================================
    print("=" * 60)
    print("1. ClickHouse 初始化")
    print("=" * 60)

    # 读取本地 SQL 文件
    local_ck_sql = r"D:\Development\IDEAWorkSpace\Github\new\crawler-backend\clickhouse-schema.sql"
    with open(local_ck_sql, "rb") as f:
        ck_sql_content = f.read()

    # 压缩后 base64 编码（避免命令行太长）
    import gzip
    ck_sql_compressed = gzip.compress(ck_sql_content)
    ck_sql_b64 = base64.b64encode(ck_sql_compressed).decode()

    # 分块写入（避免命令行长度限制）
    chunk_size = 50000
    chunks = [ck_sql_b64[i:i+chunk_size] for i in range(0, len(ck_sql_b64), chunk_size)]
    print(f"  CK SQL: {len(ck_sql_content)} bytes, {len(chunks)} chunks")

    # 创建文件
    run("rm -f /tmp/ck_schema.sql.gz.b64")
    for i, chunk in enumerate(chunks):
        run(f"echo '{chunk}' >> /tmp/ck_schema.sql.gz.b64")

    # 解码并解压
    run("base64 -d /tmp/ck_schema.sql.gz.b64 | gunzip > /tmp/ck_schema.sql")

    # 复制到容器
    run("sudo docker cp /tmp/ck_schema.sql gudao-clickhouse:/tmp/ck_schema.sql")

    # 执行（用 clickhouse-client）
    # 先确保数据库存在
    run("curl -s http://100.81.7.96:8123/ --data 'CREATE DATABASE IF NOT EXISTS crawler' -u 'default:pamirs@123'")

    # 用 docker exec 执行 SQL
    cmd = '''sudo docker exec gudao-clickhouse bash -c 'clickhouse-client --host 127.0.0.1 --port 9000 --user default --password pamirs@123 --database crawler --multiquery < /tmp/ck_schema.sql 2>&1 | tail -20' '''
    out, err = run(cmd, timeout=180)
    print(f"  CK 执行结果: {out}")

    # ========================================================================
    # 2. openGauss: 执行 schema-full-rebuild.sql
    # ========================================================================
    print("\n" + "=" * 60)
    print("2. openGauss 初始化")
    print("=" * 60)

    local_pg_sql = r"D:\Development\IDEAWorkSpace\Github\new\crawler-backend\schema-full-rebuild.sql"
    with open(local_pg_sql, "rb") as f:
        pg_sql_content = f.read()

    pg_sql_compressed = gzip.compress(pg_sql_content)
    pg_sql_b64 = base64.b64encode(pg_sql_compressed).decode()

    chunks = [pg_sql_b64[i:i+chunk_size] for i in range(0, len(pg_sql_b64), chunk_size)]
    print(f"  PG SQL: {len(pg_sql_content)} bytes, {len(chunks)} chunks")

    run("rm -f /tmp/pg_schema.sql.gz.b64")
    for i, chunk in enumerate(chunks):
        run(f"echo '{chunk}' >> /tmp/pg_schema.sql.gz.b64")

    run("base64 -d /tmp/pg_schema.sql.gz.b64 | gunzip > /tmp/pg_schema.sql")
    run("sudo docker cp /tmp/pg_schema.sql gudao-opengauss:/tmp/pg_schema.sql")

    # 用 gsql 执行
    cmd = f'''sudo docker exec gudao-opengauss bash -c 'echo "{gaussdb_pwd}" | base64 -d | LD_LIBRARY_PATH=/usr/local/opengauss/lib:/usr/local/opengauss/lib/postgresql /usr/local/opengauss/bin/gsql -U gaussdb -d postgres -h 127.0.0.1 -f /tmp/pg_schema.sql 2>&1 | tail -30' '''
    out, err = run(cmd, timeout=180)
    print(f"  PG 执行结果: {out}")

    # ========================================================================
    # 3. 验证
    # ========================================================================
    print("\n" + "=" * 60)
    print("3. 验证远程数据库")
    print("=" * 60)

    # CK 验证
    out, err = run("curl -s http://100.81.7.96:8123/ --data 'SHOW TABLES FROM crawler' -u 'default:pamirs@123'")
    print(f"  CK 表: {out[:200]}..." if len(out) > 200 else f"  CK 表: {out}")

    # PG 验证
    dbuser_pwd = base64.b64encode("OpenGauss@2026".encode()).decode()
    cmd = f'''sudo docker exec gudao-opengauss bash -c 'echo "{dbuser_pwd}" | base64 -d | LD_LIBRARY_PATH=/usr/local/opengauss/lib:/usr/local/opengauss/lib/postgresql /usr/local/opengauss/bin/gsql -U dbuser -d postgres -h 100.81.7.96 -c "SELECT count(*) FROM information_schema.tables WHERE table_schema='"'"'public'"'"';" 2>&1' '''
    out, err = run(cmd)
    print(f"  PG 表数量: {out}")

    ssh.close()
    print("\n完成！")


if __name__ == '__main__':
    main()
