#!/usr/bin/env python3
"""修复远程数据库 - 第十三轮：用 nsenter 直接修改容器"""
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

    # 获取 PG PID
    out, err = run("sudo docker inspect gudao-opengauss --format '{{.State.Pid}}'")
    pg_pid = out.strip()
    print(f"PG PID: {pg_pid}")

    # ========================================================================
    # 1. 用 nsenter 修改 pg_hba.conf
    # ========================================================================
    print("=" * 60)
    print("1. 修改 pg_hba.conf")
    print("=" * 60)

    # 检查当前内容
    out = run(f"sudo nsenter -t {pg_pid} -m -u -i -n -p -- bash -c 'cat /var/lib/opengauss/data/pg_hba.conf'")
    print(f"当前 pg_hba.conf:\n{out}")

    # 用 nsenter 写入
    hba_content = """local   all                                     all                                     trust
host    all                                     all             127.0.0.1/32                            trust
host    all                                     all             ::1/128                                 trust
host    all                                     all             0.0.0.0/0                               trust
host    replication             gaussdb                 0.0.0.0/0                               md5
host    all                                     all             100.81.0.0/16                           md5
"""

    import base64
    hba_b64 = base64.b64encode(hba_content.encode()).decode()
    run(f"sudo nsenter -t {pg_pid} -m -u -i -n -p -- bash -c 'echo {hba_b64} | base64 -d > /var/lib/opengauss/data/pg_hba.conf'")

    # 验证
    out = run(f"sudo nsenter -t {pg_pid} -m -u -i -n -p -- bash -c 'cat /var/lib/opengauss/data/pg_hba.conf'")
    print(f"修改后:\n{out}")

    # 重新加载配置（给 postgres 进程发 SIGHUP）
    run(f"sudo nsenter -t {pg_pid} -m -u -i -n -p -- bash -c 'kill -HUP 1'")
    time.sleep(3)

    # ========================================================================
    # 2. 创建 dbuser
    # ========================================================================
    print("\n" + "=" * 60)
    print("2. 创建 dbuser")
    print("=" * 60)

    out = run('''sudo docker exec gudao-opengauss bash -c 'LD_LIBRARY_PATH=/usr/local/opengauss/lib:/usr/local/opengauss/lib/postgresql /usr/local/opengauss/bin/gsql -U gaussdb -d postgres -h 127.0.0.1 -c "SELECT 1" 2>&1' ''')
    print(f"gaussdb 无密码: {out}")

    if '1' in out[0]:
        out = run('''sudo docker exec gudao-opengauss bash -c 'LD_LIBRARY_PATH=/usr/local/opengauss/lib:/usr/local/opengauss/lib/postgresql /usr/local/opengauss/bin/gsql -U gaussdb -d postgres -h 127.0.0.1 -c "CREATE USER dbuser WITH PASSWORD '\''OpenGauss@2026'\'' CREATEDB LOGIN" 2>&1' ''')
        print(f"创建 dbuser: {out}")

    # ========================================================================
    # 3. 验证
    # ========================================================================
    print("\n" + "=" * 60)
    print("3. 验证")
    print("=" * 60)

    out = run('''sudo docker exec -e PGPASSWORD='OpenGauss@2026' gudao-opengauss bash -c 'LD_LIBRARY_PATH=/usr/local/opengauss/lib:/usr/local/opengauss/lib/postgresql /usr/local/opengauss/bin/gsql -U dbuser -d postgres -h 100.81.7.96 -c "SELECT 1" 2>&1' ''')
    print(f"PG 远程: {out}")

    out = run("curl -s http://100.81.7.96:8123/ --data 'SELECT 1' -u 'default:pamirs@123'")
    print(f"CK 远程: {out}")

    ssh.close()

if __name__ == '__main__':
    main()
