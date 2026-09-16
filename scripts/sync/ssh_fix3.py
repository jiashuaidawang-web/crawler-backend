#!/usr/bin/env python3
"""修复远程数据库 - 第三轮：修复 CK 配置，测试 PG 远程连接"""
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
    # 1. 修复 ClickHouse 配置
    # ========================================================================
    print("=" * 60)
    print("1. 修复 ClickHouse 配置")
    print("=" * 60)

    # 删除损坏的配置文件
    out, err = run("sudo docker exec gudao-clickhouse rm -f /etc/clickhouse-server/config.d/listen.xml")
    print(f"删除损坏的 listen.xml: {out} {err}")

    # 用 base64 方式写入正确的配置文件（避免 shell 转义问题）
    import base64

    listen_config = '<clickhouse><listen_host>0.0.0.0</listen_host><remote_url_allow_hosts><host>100.81.0.0/16</host><host>100.97.0.0/16</host></remote_url_allow_hosts></clickhouse>'
    listen_b64 = base64.b64encode(listen_config.encode()).decode()

    cmd = f'sudo docker exec gudao-clickhouse bash -c "echo {listen_b64} | base64 -d > /etc/clickhouse-server/config.d/listen.xml"'
    out, err = run(cmd)
    print(f"写入 listen.xml: {out} {err}")

    # 验证文件内容
    out, err = run("sudo docker exec gudao-clickhouse cat /etc/clickhouse-server/config.d/listen.xml")
    print(f"listen.xml 内容: {out}")

    # 恢复 users.xml（先删除密码配置，用默认空密码）
    # 先检查当前 users.xml 状态
    out, err = run("sudo docker exec gudao-clickhouse wc -l /etc/clickhouse-server/users.xml")
    print(f"users.xml 行数: {out}")

    # 如果 users.xml 被破坏了，恢复备份
    out, err = run("sudo docker exec gudao-clickhouse ls -la /etc/clickhouse-server/users.xml.bak 2>&1")
    print(f"备份文件: {out}")

    if 'users.xml.bak' in out:
        # 恢复备份但添加远程访问密码
        cmd = '''sudo docker exec gudao-clickhouse bash -c "cp /etc/clickhouse-server/users.xml.bak /etc/clickhouse-server/users.xml"'''
        run(cmd)
        print("恢复 users.xml 备份")

    # 重启 CK
    print("重启 ClickHouse ...")
    run("sudo docker restart gudao-clickhouse", timeout=30)
    time.sleep(10)

    # 检查 CK 状态
    out, err = run("sudo docker inspect gudao-clickhouse --format '{{.State.Status}}'")
    print(f"CK 状态: {out}")

    # 测试本地连接
    out, err = run("sudo docker exec gudao-clickhouse clickhouse-client --query 'SELECT 1'")
    print(f"CK 本地连接: [{out}] [{err}]")

    # 测试远程连接
    out, err = run("curl -s http://127.0.0.1:8123/ --data 'SELECT 1' -u 'default:pamirs@123'")
    print(f"CK 远程连接 (127.0.0.1): [{out}] [{err}]")

    # ========================================================================
    # 2. 测试 openGauss 远程连接
    # ========================================================================
    print("\n" + "=" * 60)
    print("2. 测试 openGauss 远程连接")
    print("=" * 60)

    # 在容器内用正确的环境变量测试
    out, err = run("sudo docker exec gudao-opengauss bash -c 'LD_LIBRARY_PATH=/usr/local/opengauss/lib:\$LD_LIBRARY_PATH /usr/local/opengauss/bin/gsql -U dbuser -d postgres -h 127.0.0.1 -c \"SELECT 1\"'")
    print(f"PG 本地连接: [{out}] [{err}]")

    # 检查 PG 监听端口
    out, err = run("sudo docker exec gudao-opengauss bash -c 'ss -tlnp | grep 5432'")
    print(f"PG 端口监听: {out}")

    # 检查 pg_hba.conf
    out, err = run("sudo docker exec gudao-opengauss bash -c 'grep -E \"^(host|local)\" /var/lib/opengauss/data/pg_hba.conf'")
    print(f"pg_hba.conf:\n{out}")

    ssh.close()
    print("\n修复完成！")

if __name__ == '__main__':
    main()
