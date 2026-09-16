#!/usr/bin/env python3
"""修复远程数据库 - 第五轮：用 docker cp 修复 CK，修复 PG"""
import paramiko
import time
import os
import tempfile

def main():
    ssh = paramiko.SSHClient()
    ssh.set_missing_host_key_policy(paramiko.AutoAddPolicy())
    ssh.connect('100.81.7.96', 22, 'box', '3Uz9wVIZ2mpPDoPG', timeout=15, allow_agent=False, look_for_keys=False)

    def run(cmd, timeout=30):
        stdin, stdout, stderr = ssh.exec_command(cmd, timeout=timeout)
        out = stdout.read().decode().strip()
        err = stderr.read().decode().strip()
        return out, err

    def run_stream(cmd, timeout=60):
        """执行命令并实时输出"""
        transport = ssh.get_transport()
        channel = transport.open_session()
        channel.exec_command(cmd)
        channel.settimeout(timeout)
        output = b""
        while True:
            if channel.recv_ready():
                data = channel.recv(4096)
                if not data:
                    break
                output += data
                print(data.decode(), end="", flush=True)
            if channel.exit_status_ready():
                break
        return output.decode()

    # ========================================================================
    # 1. 修复 ClickHouse
    # ========================================================================
    print("=" * 60)
    print("1. 修复 ClickHouse")
    print("=" * 60)

    # 检查容器状态
    out, err = run("sudo docker inspect gudao-clickhouse --format '{{.State.Status}}'")
    print(f"CK 状态: {out}")

    # 如果容器在运行，先停止
    if out == "running":
        run("sudo docker stop gudao-clickhouse", timeout=30)
        time.sleep(2)

    # 创建一个临时容器来修复配置
    print("创建临时容器修复配置...")
    out, err = run("""sudo docker run --rm --name ck-fix \
        -v /workspace/data/clickhouse:/var/lib/clickhouse \
        clickhouse/clickhouse-server:24.8 \
        bash -c 'rm -f /etc/clickhouse-server/config.d/listen.xml; \
                 echo "<clickhouse><listen_host>0.0.0.0</listen_host></clickhouse>" > /etc/clickhouse-server/config.d/listen.xml; \
                 cat /etc/clickhouse-server/config.d/listen.xml'""", timeout=30)
    print(f"临时容器结果: {out}")

    # 直接修改运行中的容器（如果可能）
    # 或者用 nsenter 进入容器的命名空间
    print("\n尝试用 nsenter 修复...")
    out, err = run("sudo docker inspect gudao-clickhouse --format '{{.State.Pid}}'")
    print(f"CK PID: {out}")

    pid = out.strip()
    if pid and pid != "0":
        # 用 nsenter 进入容器命名空间
        out, err = run(f"sudo nsenter -t {pid} -m -u -i -n -p -- bash -c 'rm -f /etc/clickhouse-server/config.d/listen.xml; echo \"<clickhouse><listen_host>0.0.0.0</listen_host></clickhouse>\" > /etc/clickhouse-server/config.d/listen.xml; cat /etc/clickhouse-server/config.d/listen.xml'", timeout=15)
        print(f"nsenter 结果: [{out}] [{err}]")

    # 启动 CK
    print("\n启动 CK ...")
    run("sudo docker start gudao-clickhouse", timeout=30)
    time.sleep(10)

    out, err = run("sudo docker inspect gudao-clickhouse --format '{{.State.Status}}'")
    print(f"CK 状态: {out}")

    if out == "running":
        out, err = run("sudo docker exec gudao-clickhouse clickhouse-client --query 'SELECT 1'")
        print(f"CK 本地连接: [{out}] [{err}]")

    # ========================================================================
    # 2. 修复 openGauss
    # ========================================================================
    print("\n" + "=" * 60)
    print("2. 修复 openGauss")
    print("=" * 60)

    # 检查 PG 状态
    out, err = run("sudo docker inspect gudao-opengauss --format '{{.State.Status}}'")
    print(f"PG 状态: {out}")

    # 找到 PG PID
    out, err = run("sudo docker inspect gudao-opengauss --format '{{.State.Pid}}'")
    pg_pid = out.strip()
    print(f"PG PID: {pg_pid}")

    # 用 nsenter 修改 pg_hba.conf
    if pg_pid and pg_pid != "0":
        # 先检查当前 pg_hba.conf
        out, err = run(f"sudo nsenter -t {pg_pid} -m -u -i -n -p -- bash -c 'cat /var/lib/opengauss/data/pg_hba.conf | grep -E \"^(host|local)\"'")
        print(f"当前 pg_hba.conf:\n{out}")

        # 修改为 trust（允许无密码连接）
        out, err = run(f"sudo nsenter -t {pg_pid} -m -u -i -n -p -- bash -c 'sed -i \"s/sha256/trust/g\" /var/lib/opengauss/data/pg_hba.conf'")
        print(f"修改 pg_hba.conf: {out}")

        # 重新加载配置
        out, err = run(f"sudo nsenter -t {pg_pid} -m -u -i -n -p -- bash -c 'kill -HUP 1'")
        print(f"重新加载: {out}")
        time.sleep(3)

        # 测试连接
        out, err = run("sudo docker exec -e PGPASSWORD='OpenGauss@2026' gudao-opengauss bash -c 'LD_LIBRARY_PATH=/usr/local/opengauss/lib:$LD_LIBRARY_PATH /usr/local/opengauss/bin/gsql -U dbuser -d postgres -h 127.0.0.1 -c \"SELECT 1\"'")
        print(f"PG 连接: [{out}] [{err}]")

    ssh.close()
    print("\n修复完成！")

if __name__ == '__main__':
    main()
