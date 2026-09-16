#!/usr/bin/env python3
"""远程数据库初始化"""
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
    # 1. ClickHouse: 配置远程访问
    # ========================================================================
    print("=" * 60)
    print("1. ClickHouse 配置远程访问")
    print("=" * 60)

    # 创建配置文件允许远程连接
    config_xml = '''<clickhouse>
    <listen_host>0.0.0.0</listen_host>
    <remote_url_allow_hosts>
        <host>100.81.0.0/16</host>
        <host>100.97.0.0/16</host>
    </remote_url_allow_hosts>
</clickhouse>'''

    cmd = f'sudo tee /var/lib/docker/volumes/gudao-clickhouse-config/_data/remote.xml << \'EOF\'\n{config_xml}\nEOF'
    out, err = run(cmd)
    print(f"创建配置文件: {out} {err}")

    # 或者直接通过 docker exec 创建
    cmd = '''sudo docker exec gudao-clickhouse bash -c "cat > /etc/clickhouse-server/config.d/remote.xml << 'EOF'
<clickhouse>
    <listen_host>0.0.0.0</listen_host>
    <remote_url_allow_hosts>
        <host>100.81.0.0/16</host>
        <host>100.97.0.0/16</host>
    </remote_url_allow_hosts>
</clickhouse>
EOF"'''
    out, err = run(cmd)
    print(f"写入配置: {out} {err}")

    # 重启 CK
    print("重启 ClickHouse ...")
    out, err = run("sudo docker restart gudao-clickhouse", timeout=30)
    print(f"重启结果: {out} {err}")
    time.sleep(5)

    # 验证 CK 监听
    out, err = run("sudo docker exec gudao-clickhouse clickhouse-client --query \"SELECT * FROM system.settings WHERE name LIKE '%listen%'\"")
    print(f"CK 监听配置:\n{out}")

    # 创建数据库
    out, err = run("sudo docker exec gudao-clickhouse clickhouse-client --query \"CREATE DATABASE IF NOT EXISTS crawler\"")
    print(f"创建数据库: {out} {err}")

    # ========================================================================
    # 2. openGauss: 配置远程访问
    # ========================================================================
    print("\n" + "=" * 60)
    print("2. openGauss 配置远程访问")
    print("=" * 60)

    # 检查 gsql 路径
    out, err = run("sudo docker exec gudao-opengauss ls -la /usr/local/opengauss/bin/gsql")
    print(f"gsql 路径: {out}")

    # 配置 pg_hba.conf 允许 TailScale 网段
    cmd = '''sudo docker exec gudao-opengauss bash -c "echo 'host    all             all             100.81.0.0/16           md5' >> /var/lib/opengauss/data/pg_hba.conf"'''
    out, err = run(cmd)
    print(f"pg_hba.conf 更新: {out} {err}")

    # 配置 listen_addresses
    cmd = '''sudo docker exec gudao-opengauss bash -c "echo \"listen_addresses = '*'\" >> /var/lib/opengauss/data/postgresql.conf"'''
    out, err = run(cmd)
    print(f"postgresql.conf 更新: {out} {err}")

    # 重启 PG
    print("重启 openGauss ...")
    out, err = run("sudo docker restart gudao-opengauss", timeout=30)
    print(f"重启结果: {out} {err}")
    time.sleep(10)

    # 验证 PG
    out, err = run("sudo docker exec gudao-opengauss /usr/local/opengauss/bin/gsql -U dbuser -d postgres -c 'SELECT 1'")
    print(f"PG 连接测试: {out} {err}")

    # ========================================================================
    # 3. 验证远程连接
    # ========================================================================
    print("\n" + "=" * 60)
    print("3. 验证远程连接")
    print("=" * 60)

    # 从本机测试远程 CK
    out, err = run("curl -s http://127.0.0.1:8123/ --data 'SELECT 1' -u 'default:pamirs@123'")
    print(f"CK 远程连接: {out} {err}")

    ssh.close()
    print("\n初始化完成！")

if __name__ == '__main__':
    main()
