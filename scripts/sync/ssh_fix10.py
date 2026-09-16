#!/usr/bin/env python3
"""修复远程数据库 - 第十轮：正确创建 dbuser"""
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

    def gsql(sql):
        """用 gaussdb 执行 SQL"""
        # 用 heredoc 方式避免转义问题
        cmd = f'''sudo docker exec gudao-opengauss bash -c 'echo "GudaoBackup_2026!" | LD_LIBRARY_PATH=/usr/local/opengauss/lib:/usr/local/opengauss/lib/postgresql /usr/local/opengauss/bin/gsql -U gaussdb -d postgres -h 127.0.0.1 -c "{sql}" 2>&1' '''
        return run(cmd, timeout=30)

    # ========================================================================
    # 1. 创建 dbuser
    # ========================================================================
    print("=" * 60)
    print("1. 创建 dbuser")
    print("=" * 60)

    # 检查用户是否存在
    out = gsql("SELECT usename FROM pg_user WHERE usename = 'dbuser'")
    print(f"检查 dbuser: {out}")

    if 'dbuser' not in out or 'does not exist' in out:
        # 创建用户
        out = gsql("CREATE USER dbuser WITH PASSWORD 'OpenGauss@2026' CREATEDB LOGIN")
        print(f"创建 dbuser: {out}")
    else:
        # 修改密码
        out = gsql("ALTER USER dbuser WITH PASSWORD 'OpenGauss@2026'")
        print(f"修改密码: {out}")

    # 授予权限
    out = gsql("ALTER USER dbuser CREATEDB")
    print(f"授予 CREATEDB: {out}")

    # ========================================================================
    # 2. 验证 dbuser 连接
    # ========================================================================
    print("\n" + "=" * 60)
    print("2. 验证 dbuser")
    print("=" * 60)

    out = run('''sudo docker exec gudao-opengauss bash -c 'echo "OpenGauss@2026" | LD_LIBRARY_PATH=/usr/local/opengauss/lib:/usr/local/opengauss/lib/postgresql /usr/local/opengauss/bin/gsql -U dbuser -d postgres -h 127.0.0.1 -c "SELECT 1" 2>&1' ''')
    print(f"dbuser 本地连接: {out}")

    # ========================================================================
    # 3. 恢复 pg_hba.conf
    # ========================================================================
    print("\n" + "=" * 60)
    print("3. 恢复 pg_hba.conf")
    print("=" * 60)

    pg_data = "/workspace/data/opengauss"

    # 恢复 md5 认证
    run(f"sudo sed -i 's/host all all 0.0.0.0\\/0 trust/host all all 0.0.0.0\\/0 md5/' {pg_data}/pg_hba.conf")
    run(f"sudo sed -i 's/local   all             all                                     trust/local   all             all                                     md5/' {pg_data}/pg_hba.conf")

    # 验证
    out = run(f"grep -E '^(host|local)' {pg_data}/pg_hba.conf")
    print(f"pg_hba.conf:\n{out}")

    # 重启 PG
    run("sudo docker restart gudao-opengauss", timeout=30)
    time.sleep(10)

    # ========================================================================
    # 4. 最终验证
    # ========================================================================
    print("\n" + "=" * 60)
    print("4. 最终验证")
    print("=" * 60)

    # PG 远程
    out = run('''sudo docker exec gudao-opengauss bash -c 'echo "OpenGauss@2026" | LD_LIBRARY_PATH=/usr/local/opengauss/lib:/usr/local/opengauss/lib/postgresql /usr/local/opengauss/bin/gsql -U dbuser -d postgres -h 100.81.7.96 -c "SELECT 1" 2>&1' ''')
    print(f"PG 远程 (100.81.7.96): {out}")

    # CK 远程
    out = run("curl -s http://100.81.7.96:8123/ --data 'SELECT 1' -u 'default:pamirs@123'")
    print(f"CK 远程: {out}")

    ssh.close()
    print("\n完成！")

if __name__ == '__main__':
    main()
