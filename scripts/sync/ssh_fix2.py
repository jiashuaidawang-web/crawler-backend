#!/usr/bin/env python3
"""修复远程数据库 - 第二轮"""
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
    # 1. 检查 CK 状态
    # ========================================================================
    print("=" * 60)
    print("1. ClickHouse 状态检查")
    print("=" * 60)

    # 检查 CK 健康状态
    out, err = run("sudo docker inspect gudao-clickhouse --format '{{.State.Status}} {{.State.Health.Status}}'")
    print(f"CK 状态: {out}")

    # 检查 CK 日志
    out, err = run("sudo docker logs --tail 20 gudao-clickhouse 2>&1")
    print(f"CK 日志:\n{out}")

    # 等待 CK 完全启动
    print("等待 CK 启动...")
    time.sleep(5)

    # 测试本地连接
    out, err = run("sudo docker exec gudao-clickhouse clickhouse-client --query 'SELECT 1'")
    print(f"CK 本地连接: [{out}] [{err}]")

    # 测试远程连接（通过 TailScale IP）
    out, err = run("curl -s http://100.81.7.96:8123/ --data 'SELECT 1' -u 'default:pamirs@123'")
    print(f"CK 远程连接: [{out}] [{err}]")

    # 如果还是失败，检查配置
    if not out or 'Exception' in err:
        print("\n检查 CK 配置文件...")
        out, err = run("sudo docker exec gudao-clickhouse cat /etc/clickhouse-server/users.xml")
        print(f"users.xml:\n{out}")
        out, err = run("sudo docker exec gudao-clickhouse cat /etc/clickhouse-server/config.d/listen.xml")
        print(f"listen.xml:\n{out}")

    # ========================================================================
    # 2. 修复 openGauss
    # ========================================================================
    print("\n" + "=" * 60)
    print("2. openGauss 修复")
    print("=" * 60)

    # 使用 PGPASSWORD 环境变量
    out, err = run("sudo docker exec -e PGPASSWORD='OpenGauss@2026' gudao-opengauss /usr/local/opengauss/bin/gsql -U dbuser -d postgres -h 127.0.0.1 -c 'SELECT 1'")
    print(f"PG 本地连接: [{out}] [{err}]")

    # 检查 pg_hba.conf 是否有 TailScale 规则
    out, err = run("sudo docker exec gudao-opengauss grep -E '(100.81|host.*all)' /var/lib/opengauss/data/pg_hba.conf")
    print(f"pg_hba.conf 规则: {out}")

    # 检查监听地址
    out, err = run("sudo docker exec gudao-opengauss grep '^listen_addresses' /var/lib/opengauss/data/postgresql.conf")
    print(f"listen_addresses: {out}")

    # 测试远程连接
    out, err = run("PGPASSWORD='OpenGauss@2026' /usr/local/opengauss/bin/gsql -h 100.81.7.96 -p 5432 -U dbuser -d postgres -c 'SELECT 1' 2>&1")
    print(f"PG 远程连接: [{out}] [{err}]")

    ssh.close()
    print("\n检查完成！")

if __name__ == '__main__':
    main()
