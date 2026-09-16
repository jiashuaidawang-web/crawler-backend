#!/usr/bin/env python3
"""修复远程数据库 - 第八轮：修复 PG 密码，验证远程连接"""
import paramiko
import time

def main():
    ssh = paramiko.SSHClient()
    ssh.set_missing_host_key_policy(paramiko.AutoAddPolicy())
    ssh.connect('100.81.7.96', 22, 'box', '3Uz9wVIZ2mpPDoPG', timeout=15, allow_agent=False, look_for_keys=False)

    def run(cmd, timeout=30):
        stdin, stdout, stderr = ssh.exec_command(cmd, timeout=timeout)
        out = stdout.read().decode().strip()
        err = stderr.read().decode().strip()
        return out, err

    # ========================================================================
    # 1. 验证 CK 远程连接
    # ========================================================================
    print("=" * 60)
    print("1. 验证 ClickHouse")
    print("=" * 60)

    # 本地测试
    out, err = run("sudo docker exec gudao-clickhouse clickhouse-client --query 'SELECT 1'")
    print(f"CK 本地: [{out}]")

    # 远程测试（通过 TailScale）
    out, err = run("curl -s http://100.81.7.96:8123/ --data 'SELECT 1' -u 'default:pamirs@123'")
    print(f"CK 远程 (100.81.7.96): [{out}]")

    # 创建数据库
    out, err = run("sudo docker exec gudao-clickhouse clickhouse-client --query 'CREATE DATABASE IF NOT EXISTS crawler'")
    print(f"创建 crawler 数据库: [{out}]")

    # 验证数据库
    out, err = run("sudo docker exec gudao-clickhouse clickhouse-client --query 'SHOW DATABASES'")
    print(f"CK 数据库: {out}")

    # ========================================================================
    # 2. 修复 openGauss 密码
    # ========================================================================
    print("\n" + "=" * 60)
    print("2. 修复 openGauss")
    print("=" * 60)

    # 尝试用 gaussdb 超管用户连接
    out, err = run("sudo docker exec -e GS_PASSWORD='GudaoBackup_2026!' gudao-opengauss bash -c 'LD_LIBRARY_PATH=/usr/local/opengauss/lib:/usr/local/opengauss/lib/postgresql /usr/local/opengauss/bin/gsql -U gaussdb -d postgres -h 127.0.0.1 -c \"SELECT 1\" 2>&1'")
    print(f"PG gaussdb 连接: [{out}] [{err}]")

    # 尝试用 psql 方式传递密码
    out, err = run("""sudo docker exec gudao-opengauss bash -c 'echo "GudaoBackup_2026!" | LD_LIBRARY_PATH=/usr/local/opengauss/lib:/usr/local/opengauss/lib/postgresql /usr/local/opengauss/bin/gsql -U gaussdb -d postgres -h 127.0.0.1 -c "SELECT 1" 2>&1'""")
    print(f"PG pipe 密码: [{out}] [{err}]")

    # 检查 pg_hba.conf 看看认证方式
    out, err = run("sudo docker exec gudao-opengauss bash -c 'grep -E \"^(host|local)\" /var/lib/opengauss/data/pg_hba.conf'")
    print(f"pg_hba.conf:\n{out}")

    # 检查 .env 文件
    out, err = run("cat /workspace/db-stack/.env")
    print(f".env: {out}")

    # 检查是否有 init 脚本创建了 dbuser
    out, err = run("ls -la /workspace/db-stack/init/pg/ 2>/dev/null || ls -la /workspace/db-stack/init/ 2>/dev/null")
    print(f"PG init 目录: {out}")

    out, err = run("cat /workspace/db-stack/init/pg/*.sql 2>/dev/null | head -20")
    print(f"PG init SQL: {out}")

    ssh.close()
    print("\n检查完成！")

if __name__ == '__main__':
    main()
