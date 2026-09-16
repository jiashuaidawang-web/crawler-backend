#!/usr/bin/env python3
"""修复远程数据库 - 第十一轮：用文件方式执行 SQL"""
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
    # 1. 创建 dbuser（用文件方式）
    # ========================================================================
    print("=" * 60)
    print("1. 创建 dbuser")
    print("=" * 60)

    # 创建 SQL 文件
    sql = """CREATE USER dbuser WITH PASSWORD 'OpenGauss@2026' CREATEDB LOGIN;
ALTER USER dbuser CREATEDB;
"""
    # 写入临时文件
    import base64
    sql_b64 = base64.b64encode(sql.encode()).decode()

    # 在远程创建 SQL 文件
    cmd = f'echo {sql_b64} | base64 -d > /tmp/create_user.sql'
    run(cmd)

    # 用 gsql 执行 SQL 文件
    cmd = '''sudo docker exec gudao-opengauss bash -c 'LD_LIBRARY_PATH=/usr/local/opengauss/lib:/usr/local/opengauss/lib/postgresql /usr/local/opengauss/bin/gsql -U gaussdb -d postgres -h 127.0.0.1 -f /tmp/create_user.sql 2>&1' '''
    out, err = run(cmd)
    print(f"创建 dbuser: [{out}] [{err}]")

    # ========================================================================
    # 2. 验证 dbuser
    # ========================================================================
    print("\n" + "=" * 60)
    print("2. 验证 dbuser")
    print("=" * 60)

    # 用 PGPASSWORD 环境变量
    cmd = '''sudo docker exec -e PGPASSWORD='OpenGauss@2026' gudao-opengauss bash -c 'LD_LIBRARY_PATH=/usr/local/opengauss/lib:/usr/local/opengauss/lib/postgresql /usr/local/opengauss/bin/gsql -U dbuser -d postgres -h 127.0.0.1 -c "SELECT 1" 2>&1' '''
    out, err = run(cmd)
    print(f"dbuser 本地: [{out}] [{err}]")

    # ========================================================================
    # 3. 验证远程连接
    # ========================================================================
    print("\n" + "=" * 60)
    print("3. 验证远程连接")
    print("=" * 60)

    cmd = '''sudo docker exec -e PGPASSWORD='OpenGauss@2026' gudao-opengauss bash -c 'LD_LIBRARY_PATH=/usr/local/opengauss/lib:/usr/local/opengauss/lib/postgresql /usr/local/opengauss/bin/gsql -U dbuser -d postgres -h 100.81.7.96 -c "SELECT 1" 2>&1' '''
    out, err = run(cmd)
    print(f"PG 远程: [{out}] [{err}]")

    out = run("curl -s http://100.81.7.96:8123/ --data 'SELECT 1' -u 'default:pamirs@123'")
    print(f"CK 远程: [{out}]")

    ssh.close()
    print("\n完成！")

if __name__ == '__main__':
    main()
