#!/usr/bin/env python3
"""设置 openGauss：创建 dbuser，验证远程连接"""
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
    # 1. 删除旧的 dbuser（如果存在）
    # ========================================================================
    print("=" * 60)
    print("1. 删除旧的 dbuser")
    print("=" * 60)

    sql = "DROP USER IF EXISTS dbuser;"
    sql_b64 = base64.b64encode(sql.encode()).decode()
    run(f"echo {sql_b64} | base64 -d > /tmp/drop_user.sql")
    run("sudo docker cp /tmp/drop_user.sql gudao-opengauss:/tmp/drop_user.sql")

    cmd = f'''sudo docker exec gudao-opengauss bash -c 'echo "{gaussdb_pwd}" | base64 -d | LD_LIBRARY_PATH=/usr/local/opengauss/lib:/usr/local/opengauss/lib/postgresql /usr/local/opengauss/bin/gsql -U gaussdb -d postgres -h 127.0.0.1 -f /tmp/drop_user.sql 2>&1' '''
    out, err = run(cmd)
    print(f"删除: {out}")

    # ========================================================================
    # 2. 创建 dbuser（用美元符号引用密码）
    # ========================================================================
    print("\n" + "=" * 60)
    print("2. 创建 dbuser")
    print("=" * 60)

    # 用 $$ 引用密码，避免特殊字符问题
    sql = "CREATE USER dbuser WITH PASSWORD $$OpenGauss@2026$$ CREATEDB LOGIN;"
    sql_b64 = base64.b64encode(sql.encode()).decode()
    run(f"echo {sql_b64} | base64 -d > /tmp/create_user.sql")
    run("sudo docker cp /tmp/create_user.sql gudao-opengauss:/tmp/create_user.sql")

    cmd = f'''sudo docker exec gudao-opengauss bash -c 'echo "{gaussdb_pwd}" | base64 -d | LD_LIBRARY_PATH=/usr/local/opengauss/lib:/usr/local/opengauss/lib/postgresql /usr/local/opengauss/bin/gsql -U gaussdb -d postgres -h 127.0.0.1 -f /tmp/create_user.sql 2>&1' '''
    out, err = run(cmd)
    print(f"创建: {out}")

    # ========================================================================
    # 3. 验证本地连接（trust）
    # ========================================================================
    print("\n" + "=" * 60)
    print("3. 验证本地连接")
    print("=" * 60)

    cmd = f'''sudo docker exec gudao-opengauss bash -c 'echo "{dbuser_pwd}" | base64 -d | LD_LIBRARY_PATH=/usr/local/opengauss/lib:/usr/local/opengauss/lib/postgresql /usr/local/opengauss/bin/gsql -U dbuser -d postgres -h 127.0.0.1 -c "SELECT 1" 2>&1' '''
    out, err = run(cmd)
    print(f"本地: {out}")

    # ========================================================================
    # 4. 验证远程连接（md5）
    # ========================================================================
    print("\n" + "=" * 60)
    print("4. 验证远程连接")
    print("=" * 60)

    cmd = f'''sudo docker exec gudao-opengauss bash -c 'echo "{dbuser_pwd}" | base64 -d | LD_LIBRARY_PATH=/usr/local/opengauss/lib:/usr/local/opengauss/lib/postgresql /usr/local/opengauss/bin/gsql -U dbuser -d postgres -h 100.81.7.96 -c "SELECT 1" 2>&1' '''
    out, err = run(cmd)
    print(f"远程: {out}")

    # ========================================================================
    # 5. 如果远程失败，尝试用 gaussdb 用户验证
    # ========================================================================
    if 'FATAL' in out or 'denied' in out:
        print("\n远程连接失败，尝试其他方式...")

        # 检查 pg_hba.conf
        out, err = run("sudo docker exec gudao-opengauss grep -E '^(host|local)' /var/lib/opengauss/data/pg_hba.conf")
        print(f"pg_hba:\n{out}")

        # 尝试用 gaussdb 用户远程连接
        cmd = f'''sudo docker exec gudao-opengauss bash -c 'echo "{gaussdb_pwd}" | base64 -d | LD_LIBRARY_PATH=/usr/local/opengauss/lib:/usr/local/opengauss/lib/postgresql /usr/local/opengauss/bin/gsql -U gaussdb -d postgres -h 100.81.7.96 -c "SELECT 1" 2>&1' '''
        out, err = run(cmd)
        print(f"gaussdb 远程: {out}")

    ssh.close()
    print("\n完成！")


if __name__ == '__main__':
    main()
