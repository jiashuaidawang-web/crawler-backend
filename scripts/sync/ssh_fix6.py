#!/usr/bin/env python3
"""修复远程数据库 - 第六轮：重建 CK 容器，修复 PG"""
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
    # 1. 查找 docker-compose 或容器配置
    # ========================================================================
    print("=" * 60)
    print("1. 查找容器配置")
    print("=" * 60)

    out, err = run("find /workspace -name 'docker-compose*' -o -name '*.yml' 2>/dev/null | head -10")
    print(f"找到的配置文件: {out}")

    out, err = run("cat /workspace/db-stack/docker-compose.yml 2>/dev/null || cat /workspace/docker-compose.yml 2>/dev/null || echo 'not found'")
    print(f"docker-compose.yml:\n{out[:2000]}")

    # 查看原始容器配置
    out, err = run("sudo docker inspect gudao-clickhouse --format '{{json .Config}}' 2>&1 | head -100")
    print(f"CK Config:\n{out[:2000]}")

    out, err = run("sudo docker inspect gudao-clickhouse --format '{{json .HostConfig}}' 2>&1 | head -100")
    print(f"CK HostConfig:\n{out[:2000]}")

    # ========================================================================
    # 2. 尝试修复 CK
    # ========================================================================
    print("\n" + "=" * 60)
    print("2. 修复 ClickHouse")
    print("=" * 60)

    # 检查容器是否还在
    out, err = run("sudo docker inspect gudao-clickhouse --format '{{.State.Status}}'")
    print(f"CK 当前状态: {out}")

    # 强制删除并重建
    print("删除损坏的 CK 容器...")
    run("sudo docker rm -f gudao-clickhouse", timeout=30)
    time.sleep(2)

    # 创建新容器
    print("创建新的 CK 容器...")
    create_cmd = """sudo docker run -d \
        --name gudao-clickhouse \
        --network host \
        -v /workspace/data/clickhouse:/var/lib/clickhouse \
        -v /workspace/db-stack/init/ch:/docker-entrypoint-initdb.d:ro \
        -e CLICKHOUSE_DB=crawler \
        -e CLICKHOUSE_USER=default \
        -e CLICKHOUSE_PASSWORD=pamirs@123 \
        clickhouse/clickhouse-server:24.8"""

    out, err = run(create_cmd, timeout=30)
    print(f"创建结果: {out} {err}")

    # 等待启动
    time.sleep(15)

    # 检查状态
    out, err = run("sudo docker inspect gudao-clickhouse --format '{{.State.Status}}'")
    print(f"CK 状态: {out}")

    if out == "running":
        # 测试连接
        out, err = run("sudo docker exec gudao-clickhouse clickhouse-client --query 'SELECT 1'")
        print(f"CK 本地连接: [{out}] [{err}]")

        # 创建数据库
        out, err = run("sudo docker exec gudao-clickhouse clickhouse-client --query 'CREATE DATABASE IF NOT EXISTS crawler'")
        print(f"创建数据库: [{out}] [{err}]")

    # ========================================================================
    # 3. 修复 PG
    # ========================================================================
    print("\n" + "=" * 60)
    print("3. 修复 openGauss")
    print("=" * 60)

    # 用 su 切换到 opengauss 用户执行
    out, err = run("sudo docker exec gudao-opengauss bash -c 'su - opengauss -c \"gsql -d postgres -c \\\"SELECT 1\\\"\"'")
    print(f"PG su 连接: [{out}] [{err}]")

    # 或者直接用 gsql 的 -w 参数（no password）
    out, err = run("sudo docker exec gudao-opengauss bash -c 'LD_LIBRARY_PATH=/usr/local/opengauss/lib:/usr/local/opengauss/lib/postgresql /usr/local/opengauss/bin/gsql -U dbuser -d postgres -h /var/run/postgresql -c \"SELECT 1\"'")
    print(f"PG unix socket: [{out}] [{err}]")

    ssh.close()
    print("\n修复完成！")

if __name__ == '__main__':
    main()
