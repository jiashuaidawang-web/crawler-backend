#!/usr/bin/env python3
"""最终：用 SQL 文件创建 dbuser"""
import paramiko
import time
import base64

def main():
    ssh = paramiko.SSHClient()
    ssh.set_missing_host_key_policy(paramiko.AutoAddPolicy())
    ssh.connect('100.81.7.96', 22, 'box', '3Uz9wVIZ2mpPDoPG', timeout=15, allow_agent=False, look_for_keys=False)

    def run(cmd, timeout=30):
        stdin, stdout, stderr = ssh.exec_command(cmd, timeout=timeout)
        out = stdout.read().decode().strip()
        err = stderr.read().decode().strip()
        return out, err

    gaussdb_pwd = base64.b64encode("GudaoBackup_2026!".encode()).decode()
    dbuser_pwd = base64.b64encode("OpenGauss@2026".encode()).decode()

    # ========================================================================
    # 1. 创建 SQL 文件
    # ========================================================================
    print("=" * 60)
    print("1. 创建 SQL 文件")
    print("=" * 60)

    sql = """CREATE USER dbuser WITH PASSWORD 'OpenGauss@2026' CREATEDB LOGIN;
"""
    sql_b64 = base64.b64encode(sql.encode()).decode()
    run(f"echo {sql_b64} | base64 -d > /tmp/create_dbuser.sql")

    # 验证文件内容
    out, err = run("cat /tmp/create_dbuser.sql")
    print(f"SQL 文件: {out}")

    # ========================================================================
    # 2. 执行 SQL 文件
    # ========================================================================
    print("\n" + "=" * 60)
    print("2. 执行 SQL 文件")
    print("=" * 60)

    cmd = f'''sudo docker exec gudao-opengauss bash -c 'echo "{gaussdb_pwd}" | base64 -d | LD_LIBRARY_PATH=/usr/local/opengauss/lib:/usr/local/opengauss/lib/postgresql /usr/local/opengauss/bin/gsql -U gaussdb -d postgres -h 127.0.0.1 -f /tmp/create_dbuser.sql 2>&1' '''
    out, err = run(cmd)
    print(f"执行结果: {out}")

    # ========================================================================
    # 3. 验证 dbuser
    # ========================================================================
    print("\n" + "=" * 60)
    print("3. 验证 dbuser")
    print("=" * 60)

    cmd = f'''sudo docker exec gudao-opengauss bash -c 'echo "{dbuser_pwd}" | base64 -d | LD_LIBRARY_PATH=/usr/local/opengauss/lib:/usr/local/opengauss/lib/postgresql /usr/local/opengauss/bin/gsql -U dbuser -d postgres -h 127.0.0.1 -c "SELECT 1" 2>&1' '''
    out, err = run(cmd)
    print(f"dbuser 本地: {out}")

    # ========================================================================
    # 4. 验证远程
    # ========================================================================
    print("\n" + "=" * 60)
    print("4. 验证远程连接")
    print("=" * 60)

    cmd = f'''sudo docker exec gudao-opengauss bash -c 'echo "{dbuser_pwd}" | base64 -d | LD_LIBRARY_PATH=/usr/local/opengauss/lib:/usr/local/opengauss/lib/postgresql /usr/local/opengauss/bin/gsql -U dbuser -d postgres -h 100.81.7.96 -c "SELECT 1" 2>&1' '''
    out, err = run(cmd)
    print(f"dbuser 远程: {out}")

    # ========================================================================
    # 5. 验证 CK
    # ========================================================================
    print("\n" + "=" * 60)
    print("5. 验证 ClickHouse")
    print("=" * 60)
    out, err = run("curl -s http://100.81.7.96:8123/ --data 'SHOW DATABASES' -u 'default:pamirs@123'")
    print(f"CK 数据库: {out}")

    ssh.close()
    print("\n完成！")


if __name__ == '__main__':
    main()
