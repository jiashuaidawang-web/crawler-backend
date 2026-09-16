#!/usr/bin/env python3
"""最终：创建 dbuser，验证远程连接"""
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

    def gsql(sql, user="gaussdb", pwd_b64=gaussdb_pwd, host="127.0.0.1"):
        cmd = f'''sudo docker exec gudao-opengauss bash -c 'echo "{pwd_b64}" | base64 -d | LD_LIBRARY_PATH=/usr/local/opengauss/lib:/usr/local/opengauss/lib/postgresql /usr/local/opengauss/bin/gsql -U {user} -d postgres -h {host} -c "{sql}" 2>&1' '''
        return run(cmd)

    # ========================================================================
    # 1. 创建 dbuser
    # ========================================================================
    print("=" * 60)
    print("1. 创建 dbuser")
    print("=" * 60)
    out, err = gsql("CREATE USER dbuser WITH PASSWORD 'OpenGauss@2026' CREATEDB LOGIN")
    print(f"CREATE USER: {out}")

    # ========================================================================
    # 2. 验证 dbuser 本地
    # ========================================================================
    print("\n" + "=" * 60)
    print("2. 验证 dbuser 本地连接")
    print("=" * 60)
    out, err = gsql("SELECT 1", user="dbuser", pwd_b64=dbuser_pwd)
    print(f"本地 SELECT 1: {out}")

    # ========================================================================
    # 3. 验证 dbuser 远程
    # ========================================================================
    print("\n" + "=" * 60)
    print("3. 验证 dbuser 远程连接")
    print("=" * 60)
    out, err = gsql("SELECT 1", user="dbuser", pwd_b64=dbuser_pwd, host="100.81.7.96")
    print(f"远程 SELECT 1: {out}")

    # ========================================================================
    # 4. 创建数据库表（schema）
    # ========================================================================
    print("\n" + "=" * 60)
    print("4. 初始化数据库表")
    print("=" * 60)
    # 检查表是否存在
    out, err = gsql("SELECT tablename FROM pg_tables WHERE tableowner='dbuser' LIMIT 5", user="dbuser", pwd_b64=dbuser_pwd)
    print(f"现有表: {out}")

    # ========================================================================
    # 5. 验证 CK 远程
    # ========================================================================
    print("\n" + "=" * 60)
    print("5. 验证 ClickHouse")
    print("=" * 60)
    out, err = run("curl -s http://100.81.7.96:8123/ --data 'SELECT 1' -u 'default:pamirs@123'")
    print(f"CK 远程: {out}")

    out, err = run("curl -s http://100.81.7.96:8123/ --data 'SHOW DATABASES' -u 'default:pamirs@123'")
    print(f"CK 数据库: {out}")

    ssh.close()
    print("\n完成！")


if __name__ == '__main__':
    main()
