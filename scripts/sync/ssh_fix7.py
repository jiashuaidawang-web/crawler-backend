#!/usr/bin/env python3
"""修复远程数据库 - 第七轮：配置 CK 远程访问，修复 PG 连接"""
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
    # 1. 配置 ClickHouse 远程访问
    # ========================================================================
    print("=" * 60)
    print("1. 配置 ClickHouse 远程访问")
    print("=" * 60)

    # 创建监听配置
    listen_xml = '<clickhouse><listen_host>0.0.0.0</listen_host><remote_url_allow_hosts><host>100.81.0.0/16</host><host>100.97.0.0/16</host></remote_url_allow_hosts></clickhouse>'

    # 用 base64 避免 shell 转义问题
    import base64
    b64 = base64.b64encode(listen_xml.encode()).decode()

    cmd = f'sudo docker exec gudao-clickhouse bash -c "mkdir -p /etc/clickhouse-server/config.d && echo {b64} | base64 -d > /etc/clickhouse-server/config.d/remote.xml"'
    out, err = run(cmd)
    print(f"写入远程配置: {out} {err}")

    # 验证
    out, err = run("sudo docker exec gudao-clickhouse cat /etc/clickhouse-server/config.d/remote.xml")
    print(f"remote.xml: {out}")

    # 设置密码（通过 users.d 目录）
    password = "pamirs@123"
    users_xml = f'''<?xml version="1.0"?>
<clickhouse>
    <users>
        <default>
            <password>{password}</password>
            <networks>
                <ip>::/0</ip>
            </networks>
            <profile>default</profile>
            <quota>default</quota>
        </default>
    </users>
</clickhouse>'''

    # 先检查当前 users.xml
    out, err = run("sudo docker exec gudao-clickhouse cat /etc/clickhouse-server/users.xml | head -40")
    print(f"当前 users.xml:\n{out}")

    # 重启 CK 使配置生效
    print("重启 CK ...")
    run("sudo docker restart gudao-clickhouse", timeout=30)
    time.sleep(10)

    # 测试本地连接（无密码）
    out, err = run("sudo docker exec gudao-clickhouse clickhouse-client --query 'SELECT 1'")
    print(f"CK 本地无密码: [{out}] [{err}]")

    # 测试远程连接（有密码）
    out, err = run("curl -s http://127.0.0.1:8123/ --data 'SELECT 1' -u 'default:pamirs@123'")
    print(f"CK 远程密码: [{out}] [{err}]")

    # ========================================================================
    # 2. 修复 openGauss
    # ========================================================================
    print("\n" + "=" * 60)
    print("2. 修复 openGauss")
    print("=" * 60)

    # 检查 .env 文件中的密码
    out, err = run("cat /workspace/db-stack/.env 2>/dev/null")
    print(f".env 文件: {out}")

    # 检查 PG 进程
    out, err = run("sudo docker exec gudao-opengauss ps aux | grep gauss")
    print(f"PG 进程: {out}")

    # 检查 PG 监听
    out, err = run("sudo docker exec gudao-opengauss ss -tlnp 2>/dev/null || sudo docker exec gudao-opengauss netstat -tlnp 2>/dev/null")
    print(f"PG 端口: {out}")

    # 尝试用 PGPASSWORD 连接
    for pwd in ["OpenGauss@2026", "openGauss@123", "Gauss@123456", ""]:
        out, err = run(f"sudo docker exec -e PGPASSWORD='{pwd}' gudao-opengauss bash -c 'LD_LIBRARY_PATH=/usr/local/opengauss/lib:/usr/local/opengauss/lib/postgresql /usr/local/opengauss/bin/gsql -U dbuser -d postgres -h 127.0.0.1 -c \"SELECT 1\" 2>&1'")
        print(f"PG 密码 '{pwd}': [{out}] [{err}]")
        if '1' in out and 'error' not in err.lower():
            print(f"  -> 密码正确!")
            break

    ssh.close()
    print("\n修复完成！")

if __name__ == '__main__':
    main()
