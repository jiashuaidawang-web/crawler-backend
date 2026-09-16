#!/usr/bin/env python3
"""验证远程数据库状态"""
import paramiko
import time
import base64

def main():
    ssh = paramiko.SSHClient()
    ssh.set_missing_host_key_policy(paramiko.AutoAddPolicy())
    ssh.connect('100.81.7.96', 22, 'box', '3Uz9wVIZ2mpPDoPG', timeout=15, allow_agent=False, look_for_keys=False)

    def run(cmd, timeout=60):
        stdin, stdout, stderr = ssh.exec_command(cmd, timeout=timeout)
        out = stdout.read().decode().strip()
        err = stderr.read().decode().strip()
        return out, err

    gaussdb_pwd = base64.b64encode("GudaoBackup_2026!".encode()).decode()
    dbuser_pwd = base64.b64encode("OpenGauss@2026".encode()).decode()

    # ========================================================================
    # 1. ClickHouse: 列出所有表
    # ========================================================================
    print("=" * 60)
    print("1. ClickHouse 表")
    print("=" * 60)
    out, err = run("curl -s http://100.81.7.96:8123/ --data 'SHOW TABLES FROM crawler' -u 'default:pamirs@123'")
    print(out)

    # ========================================================================
    # 2. openGauss: 用 gaussdb 检查表
    # ========================================================================
    print("\n" + "=" * 60)
    print("2. openGauss 表 (gaussdb 用户)")
    print("=" * 60)

    def gsql(sql, user="gaussdb", pwd_b64=gaussdb_pwd, host="127.0.0.1"):
        cmd = f'''sudo docker exec gudao-opengauss bash -c 'echo "{pwd_b64}" | base64 -d | LD_LIBRARY_PATH=/usr/local/opengauss/lib:/usr/local/opengauss/lib/postgresql /usr/local/opengauss/bin/gsql -U {user} -d postgres -h {host} -c "{sql}" 2>&1' '''
        return run(cmd)

    out, err = gsql("SELECT tablename FROM pg_tables WHERE table_schema='public' ORDER BY tablename;")
    print(out)

    # ========================================================================
    # 3. openGauss: 用 dbuser 远程检查
    # ========================================================================
    print("\n" + "=" * 60)
    print("3. openGauss 表 (dbuser 远程)")
    print("=" * 60)
    cmd = f'''sudo docker exec gudao-opengauss bash -c 'echo "{dbuser_pwd}" | base64 -d | LD_LIBRARY_PATH=/usr/local/opengauss/lib:/usr/local/opengauss/lib/postgresql /usr/local/opengauss/bin/gsql -U dbuser -d postgres -h 100.81.7.96 -c "SELECT tablename FROM pg_tables WHERE table_schema='"'"'public'"'"' ORDER BY tablename;" 2>&1' '''
    out, err = run(cmd)
    print(out)

    # ========================================================================
    # 4. 检查 dbuser 权限
    # ========================================================================
    print("\n" + "=" * 60)
    print("4. dbuser 权限")
    print("=" * 60)
    out, err = gsql("SELECT usename, usesysid, usecreatedb, userepl FROM pg_user WHERE usename='dbuser';")
    print(out)

    # ========================================================================
    # 5. 检查表所有者
    # ========================================================================
    print("\n" + "=" * 60)
    print("5. 表所有者")
    print("=" * 60)
    out, err = gsql("SELECT tableowner, count(*) FROM pg_tables WHERE table_schema='public' GROUP BY tableowner;")
    print(out)

    ssh.close()
    print("\n完成！")


if __name__ == '__main__':
    main()
