#!/usr/bin/env python3
"""修复 openGauss md5 认证问题"""
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

    def gsql(sql, user="gaussdb", pwd_b64=gaussdb_pwd, host="127.0.0.1"):
        cmd = f'''sudo docker exec gudao-opengauss bash -c 'echo "{pwd_b64}" | base64 -d | LD_LIBRARY_PATH=/usr/local/opengauss/lib:/usr/local/opengauss/lib/postgresql /usr/local/opengauss/bin/gsql -U {user} -d postgres -h {host} -c "{sql}" 2>&1' '''
        return run(cmd)

    # ========================================================================
    # 1. 检查密码加密方式
    # ========================================================================
    print("=" * 60)
    print("1. 检查密码加密方式")
    print("=" * 60)
    out, err = gsql("SHOW password_encryption_type;")
    print(f"password_encryption_type: {out}")

    # ========================================================================
    # 2. 设置为 md5
    # ========================================================================
    print("\n" + "=" * 60)
    print("2. 设置 password_encryption_type = 1 (md5)")
    print("=" * 60)
    out, err = gsql("ALTER SYSTEM SET password_encryption_type = 1;")
    print(f"ALTER SYSTEM: {out}")

    # ========================================================================
    # 3. 重新加载配置
    # ========================================================================
    print("\n" + "=" * 60)
    print("3. 重新加载配置")
    print("=" * 60)
    out, err = gsql("SELECT pg_reload_conf();")
    print(f"pg_reload_conf: {out}")

    # ========================================================================
    # 4. 重置 dbuser 密码（用 md5 方式存储）
    # ========================================================================
    print("\n" + "=" * 60)
    print("4. 重置 dbuser 密码")
    print("=" * 60)
    out, err = gsql("ALTER USER dbuser WITH PASSWORD $$OpenGauss@2026$$;")
    print(f"ALTER USER: {out}")

    # ========================================================================
    # 5. 验证本地连接
    # ========================================================================
    print("\n" + "=" * 60)
    print("5. 验证本地连接")
    print("=" * 60)
    dbuser_pwd = base64.b64encode("OpenGauss@2026".encode()).decode()
    cmd = f'''sudo docker exec gudao-opengauss bash -c 'echo "{dbuser_pwd}" | base64 -d | LD_LIBRARY_PATH=/usr/local/opengauss/lib:/usr/local/opengauss/lib/postgresql /usr/local/opengauss/bin/gsql -U dbuser -d postgres -h 127.0.0.1 -c "SELECT 1" 2>&1' '''
    out, err = run(cmd)
    print(f"本地: {out}")

    # ========================================================================
    # 6. 验证远程连接
    # ========================================================================
    print("\n" + "=" * 60)
    print("6. 验证远程连接")
    print("=" * 60)
    cmd = f'''sudo docker exec gudao-opengauss bash -c 'echo "{dbuser_pwd}" | base64 -d | LD_LIBRARY_PATH=/usr/local/opengauss/lib:/usr/local/opengauss/lib/postgresql /usr/local/opengauss/bin/gsql -U dbuser -d postgres -h 100.81.7.96 -c "SELECT 1" 2>&1' '''
    out, err = run(cmd)
    print(f"远程: {out}")

    # ========================================================================
    # 7. 如果还是失败，检查 pg_authid
    # ========================================================================
    if 'FATAL' in out or 'denied' in out:
        print("\n" + "=" * 60)
        print("7. 检查 pg_authid")
        print("=" * 60)
        out, err = gsql("SELECT usename, substr(passwd, 1, 20) as pwd_prefix FROM pg_authid WHERE usename IN ('dbuser', 'gaussdb');")
        print(f"pg_authid: {out}")

        # 尝试重启容器让配置生效
        print("\n重启容器...")
        out, err = run("sudo docker restart gudao-opengauss")
        print(f"restart: {out}")
        time.sleep(10)

        # 再次验证
        cmd = f'''sudo docker exec gudao-opengauss bash -c 'echo "{dbuser_pwd}" | base64 -d | LD_LIBRARY_PATH=/usr/local/opengauss/lib:/usr/local/opengauss/lib/postgresql /usr/local/opengauss/bin/gsql -U dbuser -d postgres -h 100.81.7.96 -c "SELECT 1" 2>&1' '''
        out, err = run(cmd)
        print(f"远程(重启后): {out}")

    ssh.close()
    print("\n完成！")


if __name__ == '__main__':
    main()
