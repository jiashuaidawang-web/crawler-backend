#!/usr/bin/env python3
"""重置 dbuser 密码并验证"""
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
    # 1. 重置密码
    # ========================================================================
    print("=" * 60)
    print("1. 重置 dbuser 密码")
    print("=" * 60)
    out, err = gsql("ALTER USER dbuser WITH PASSWORD 'OpenGauss@2026'")
    print(f"ALTER USER: {out}")

    # ========================================================================
    # 2. 验证本地
    # ========================================================================
    print("\n" + "=" * 60)
    print("2. 验证本地连接")
    print("=" * 60)
    out, err = gsql("SELECT 1", user="dbuser", pwd_b64=dbuser_pwd)
    print(f"本地: {out}")

    # ========================================================================
    # 3. 验证远程
    # ========================================================================
    print("\n" + "=" * 60)
    print("3. 验证远程连接")
    print("=" * 60)
    out, err = gsql("SELECT 1", user="dbuser", pwd_b64=dbuser_pwd, host="100.81.7.96")
    print(f"远程: {out}")

    # ========================================================================
    # 4. 检查 pg_hba.conf
    # ========================================================================
    print("\n" + "=" * 60)
    print("4. 检查 pg_hba.conf")
    print("=" * 60)
    out, err = run("sudo docker exec gudao-opengauss grep -E '^(host|local)' /var/lib/opengauss/data/pg_hba.conf")
    print(f"pg_hba:\n{out}")

    # ========================================================================
    # 5. 检查用户信息
    # ========================================================================
    print("\n" + "=" * 60)
    print("5. 检查用户")
    print("=" * 60)
    out, err = gsql("SELECT usename, usesysid FROM pg_user WHERE usename='dbuser'")
    print(f"dbuser: {out}")

    ssh.close()
    print("\n完成！")


if __name__ == '__main__':
    main()
