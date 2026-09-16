#!/usr/bin/env python3
"""修复 openGauss：直接修改 postgresql.conf 设置 md5，然后重新创建用户"""
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

    # ========================================================================
    # 1. 直接修改 postgresql.conf，设置 password_encryption_type = 1
    # ========================================================================
    print("=" * 60)
    print("1. 修改 postgresql.conf")
    print("=" * 60)

    # 先查看当前配置
    out, err = run("sudo docker exec gudao-opengauss grep -n 'password_encryption_type' /var/lib/opengauss/data/postgresql.conf")
    print(f"当前: {out}")

    # 用 sed 修改：如果有就替换，没有就追加
    cmd = '''sudo docker exec gudao-opengauss bash -c "sed -i 's/^#*password_encryption_type.*/password_encryption_type = 1/' /var/lib/opengauss/data/postgresql.conf && grep password_encryption_type /var/lib/opengauss/data/postgresql.conf"'''
    out, err = run(cmd)
    print(f"修改后: {out}")

    # 如果没找到，追加
    if 'password_encryption_type' not in out:
        cmd = '''sudo docker exec gudao-opengauss bash -c "echo 'password_encryption_type = 1' >> /var/lib/opengauss/data/postgresql.conf && echo 'appended'"'''
        out, err = run(cmd)
        print(f"追加: {out}")

    # ========================================================================
    # 2. 重启容器
    # ========================================================================
    print("\n" + "=" * 60)
    print("2. 重启容器")
    print("=" * 60)
    out, err = run("sudo docker restart gudao-opengauss")
    print(f"restart: {out}")
    time.sleep(15)

    # ========================================================================
    # 3. 验证配置生效
    # ========================================================================
    print("\n" + "=" * 60)
    print("3. 验证配置")
    print("=" * 60)

    def gsql(sql, user="gaussdb", pwd_b64=gaussdb_pwd, host="127.0.0.1"):
        cmd = f'''sudo docker exec gudao-opengauss bash -c 'echo "{pwd_b64}" | base64 -d | LD_LIBRARY_PATH=/usr/local/opengauss/lib:/usr/local/opengauss/lib/postgresql /usr/local/opengauss/bin/gsql -U {user} -d postgres -h {host} -c "{sql}" 2>&1' '''
        return run(cmd)

    out, err = gsql("SHOW password_encryption_type;")
    print(f"password_encryption_type: {out}")

    # ========================================================================
    # 4. 删除并重新创建 dbuser（现在会用 md5 存储密码）
    # ========================================================================
    print("\n" + "=" * 60)
    print("4. 重新创建 dbuser")
    print("=" * 60)

    # 删除
    out, err = gsql("DROP USER IF EXISTS dbuser;")
    print(f"删除: {out}")

    # 创建（用单引号，通过 SQL 文件传递避免 shell 解释）
    sql = "CREATE USER dbuser WITH PASSWORD 'OpenGauss@2026' CREATEDB LOGIN;"
    sql_b64 = base64.b64encode(sql.encode()).decode()
    run(f"echo {sql_b64} | base64 -d > /tmp/create_dbuser.sql")
    run("sudo docker cp /tmp/create_dbuser.sql gudao-opengauss:/tmp/create_dbuser.sql")

    cmd = f'''sudo docker exec gudao-opengauss bash -c 'echo "{gaussdb_pwd}" | base64 -d | LD_LIBRARY_PATH=/usr/local/opengauss/lib:/usr/local/opengauss/lib/postgresql /usr/local/opengauss/bin/gsql -U gaussdb -d postgres -h 127.0.0.1 -f /tmp/create_dbuser.sql 2>&1' '''
    out, err = run(cmd)
    print(f"创建: {out}")

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
    # 7. 如果还是失败，尝试 scram-sha-256
    # ========================================================================
    if 'FATAL' in out or 'denied' in out:
        print("\n" + "=" * 60)
        print("7. 尝试修改 pg_hba 为 scram-sha-256")
        print("=" * 60)

        # 备份并修改 pg_hba.conf
        cmd = '''sudo docker exec gudao-opengauss bash -c "cp /var/lib/opengauss/data/pg_hba.conf /var/lib/opengauss/data/pg_hba.conf.bak && sed -i 's/md5/scram-sha-256/g' /var/lib/opengauss/data/pg_hba.conf && grep -E '^(host|local)' /var/lib/opengauss/data/pg_hba.conf"'''
        out, err = run(cmd)
        print(f"pg_hba 修改后:\n{out}")

        # 改回 password_encryption_type = 2
        cmd = '''sudo docker exec gudao-opengauss bash -c "sed -i 's/^password_encryption_type.*/password_encryption_type = 2/' /var/lib/opengauss/data/postgresql.conf && grep password_encryption_type /var/lib/opengauss/data/postgresql.conf"'''
        out, err = run(cmd)
        print(f"加密方式改回: {out}")

        # 重启
        run("sudo docker restart gudao-opengauss")
        time.sleep(15)

        # 重置密码
        cmd = f'''sudo docker exec gudao-opengauss bash -c 'echo "{gaussdb_pwd}" | base64 -d | LD_LIBRARY_PATH=/usr/local/opengauss/lib:/usr/local/opengauss/lib/postgresql /usr/local/opengauss/bin/gsql -U gaussdb -d postgres -h 127.0.0.1 -c "ALTER USER dbuser WITH PASSWORD '\''OpenGauss@2026'\'';" 2>&1' '''
        out, err = run(cmd)
        print(f"重置密码: {out}")

        # 再次验证远程
        cmd = f'''sudo docker exec gudao-opengauss bash -c 'echo "{dbuser_pwd}" | base64 -d | LD_LIBRARY_PATH=/usr/local/opengauss/lib:/usr/local/opengauss/lib/postgresql /usr/local/opengauss/bin/gsql -U dbuser -d postgres -h 100.81.7.96 -c "SELECT 1" 2>&1' '''
        out, err = run(cmd)
        print(f"远程(scram-sha-256): {out}")

    ssh.close()
    print("\n完成！")


if __name__ == '__main__':
    main()
