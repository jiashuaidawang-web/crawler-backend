#!/usr/bin/env python3
"""修复远程数据库 - 第十四轮：正确配置 PG 认证"""
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

    out, err = run("sudo docker inspect gudao-opengauss --format '{{.State.Pid}}'")
    pg_pid = out.strip()

    # ========================================================================
    # 1. 配置 pg_hba.conf（本地 trust，远程 md5）
    # ========================================================================
    print("=" * 60)
    print("1. 配置 pg_hba.conf")
    print("=" * 60)

    hba_content = """local   all             all                                     trust
host    all             all             127.0.0.1/32            trust
host    all             all             ::1/128                 trust
host    all             all             100.81.0.0/16           md5
host    all             all             0.0.0.0/0               md5
host    replication     gaussdb         0.0.0.0/0               md5
"""

    import base64
    hba_b64 = base64.b64encode(hba_content.encode()).decode()
    run(f"sudo nsenter -t {pg_pid} -m -u -i -n -p -- bash -c 'echo {hba_b64} | base64 -d > /var/lib/opengauss/data/pg_hba.conf'")

    # 重启 PG 容器使配置生效
    print("重启 PG ...")
    run("sudo docker restart gudao-opengauss", timeout=30)
    time.sleep(15)

    # ========================================================================
    # 2. 创建 dbuser
    # ========================================================================
    print("\n" + "=" * 60)
    print("2. 创建 dbuser")
    print("=" * 60)

    # 本地 trust 连接
    out = run('''sudo docker exec gudao-opengauss bash -c 'LD_LIBRARY_PATH=/usr/local/opengauss/lib:/usr/local/opengauss/lib/postgresql /usr/local/opengauss/bin/gsql -U gaussdb -d postgres -h 127.0.0.1 -c "SELECT 1" 2>&1' ''')
    print(f"gaussdb 本地: {out}")

    if '1' in out[0]:
        # 检查 dbuser 是否存在
        out = run('''sudo docker exec gudao-opengauss bash -c 'LD_LIBRARY_PATH=/usr/local/opengauss/lib:/usr/local/opengauss/lib/postgresql /usr/local/opengauss/bin/gsql -U gaussdb -d postgres -h 127.0.0.1 -c "SELECT usename FROM pg_user WHERE usename = '"'"'dbuser'"'"'" 2>&1' ''')
        print(f"检查 dbuser: {out}")

        if 'dbuser' not in out[0]:
            out = run('''sudo docker exec gudao-opengauss bash -c 'LD_LIBRARY_PATH=/usr/local/opengauss/lib:/usr/local/opengauss/lib/postgresql /usr/local/opengauss/bin/gsql -U gaussdb -d postgres -h 127.0.0.1 -c "CREATE USER dbuser WITH PASSWORD '"'"'OpenGauss@2026'"'"' CREATEDB LOGIN" 2>&1' ''')
            print(f"创建 dbuser: {out}")

    # ========================================================================
    # 3. 验证
    # ========================================================================
    print("\n" + "=" * 60)
    print("3. 验证连接")
    print("=" * 60)

    # 本地 trust
    out = run('''sudo docker exec gudao-opengauss bash -c 'LD_LIBRARY_PATH=/usr/local/opengauss/lib:/usr/local/opengauss/lib/postgresql /usr/local/opengauss/bin/gsql -U gaussdb -d postgres -h 127.0.0.1 -c "SELECT 1" 2>&1' ''')
    print(f"PG 本地 trust: {out}")

    # 远程 md5
    out = run('''sudo docker exec -e PGPASSWORD='OpenGauss@2026' gudao-opengauss bash -c 'LD_LIBRARY_PATH=/usr/local/opengauss/lib:/usr/local/opengauss/lib/postgresql /usr/local/opengauss/bin/gsql -U dbuser -d postgres -h 100.81.7.96 -c "SELECT 1" 2>&1' ''')
    print(f"PG 远程 md5: {out}")

    out = run("curl -s http://100.81.7.96:8123/ --data 'SELECT 1' -u 'default:pamirs@123'")
    print(f"CK 远程: {out}")

    ssh.close()
    print("\n完成！")

if __name__ == '__main__':
    main()
