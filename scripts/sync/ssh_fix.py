#!/usr/bin/env python3
"""修复远程数据库问题"""
import paramiko
import time
import hashlib

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
    # 1. 检查 CK 当前密码配置
    # ========================================================================
    print("=" * 60)
    print("1. 检查 ClickHouse 密码配置")
    print("=" * 60)

    out, err = run("sudo docker exec gudao-clickhouse grep -A 5 'default' /etc/clickhouse-server/users.xml 2>&1 | head -20")
    print(f"default 用户配置:\n{out}")

    # ========================================================================
    # 2. 设置 CK 密码 (使用 plaintext_password 方式)
    # ========================================================================
    print("\n" + "=" * 60)
    print("2. 设置 ClickHouse 密码")
    print("=" * 60)

    password = "pamirs@123"
    sha256_hash = hashlib.sha256(password.encode()).hexdigest()
    print(f"密码 SHA256: {sha256_hash}")

    # 写入用户配置（允许任意 IP 连接）
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

    # 先备份原文件
    run("sudo docker exec gudao-clickhouse cp /etc/clickhouse-server/users.xml /etc/clickhouse-server/users.xml.bak")

    # 写入新配置
    cmd = f'sudo docker exec gudao-clickhouse bash -c "cat > /etc/clickhouse-server/users.xml << \'EOF\'\n{users_xml}\nEOF"'
    out, err = run(cmd)
    print(f"写入用户配置: {out} {err}")

    # 确保监听配置正确
    listen_xml = '''<?xml version="1.0"?>
<clickhouse>
    <listen_host>0.0.0.0</listen_host>
</clickhouse>'''

    cmd = f'sudo docker exec gudao-clickhouse bash -c "cat > /etc/clickhouse-server/config.d/listen.xml << \'EOF\'\n{listen_xml}\nEOF"'
    out, err = run(cmd)
    print(f"写入监听配置: {out} {err}")

    # 重启 CK
    print("重启 ClickHouse ...")
    run("sudo docker restart gudao-clickhouse", timeout=30)
    time.sleep(8)

    # 验证密码
    out, err = run("curl -s http://127.0.0.1:8123/ --data 'SELECT 1' -u 'default:pamirs@123'")
    print(f"CK 密码验证: [{out}] [{err}]")

    # ========================================================================
    # 3. 修复 openGauss
    # ========================================================================
    print("\n" + "=" * 60)
    print("3. 修复 openGauss")
    print("=" * 60)

    # 检查 PG 状态
    out, err = run("sudo docker exec gudao-opengauss ps aux | grep -i gauss | head -5")
    print(f"PG 进程: {out}")

    # 检查 libcjson
    out, err = run("sudo docker exec gudao-opengauss find / -name 'libcjson*' 2>/dev/null")
    print(f"libcjson: {out}")

    # 检查依赖
    out, err = run("sudo docker exec gudao-opengauss ldd /usr/local/opengauss/bin/gsql 2>&1 | grep -i 'not found'")
    print(f"缺失库: {out}")

    # 尝试用 env 运行
    out, err = run("sudo docker exec gudao-opengauss bash -c 'LD_LIBRARY_PATH=/usr/local/opengauss/lib:/usr/local/opengauss/lib/postgresql:/lib64:/usr/lib64 /usr/local/opengauss/bin/gsql -U dbuser -d postgres -c \"SELECT 1\"'")
    print(f"PG 连接: [{out}] [{err}]")

    # 检查 pg_hba.conf
    out, err = run("sudo docker exec gudao-opengauss cat /var/lib/opengauss/data/pg_hba.conf 2>&1 | head -20")
    print(f"pg_hba.conf:\n{out}")

    # 检查 postgresql.conf 的 listen_addresses
    out, err = run("sudo docker exec gudao-opengauss grep 'listen_addresses' /var/lib/opengauss/data/postgresql.conf 2>&1")
    print(f"listen_addresses: {out} {err}")

    ssh.close()
    print("\n检查完成！")

if __name__ == '__main__':
    main()
