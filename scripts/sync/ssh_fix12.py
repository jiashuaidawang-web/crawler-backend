#!/usr/bin/env python3
"""修复远程数据库 - 第十二轮：用 trust 方式创建用户"""
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

    pg_data = "/workspace/data/opengauss"

    # ========================================================================
    # 1. 临时改为 trust
    # ========================================================================
    print("=" * 60)
    print("1. 临时设置 trust 认证")
    print("=" * 60)

    # 备份
    run(f"sudo cp {pg_data}/pg_hba.conf {pg_data}/pg_hba.conf.bak")

    # 全部改为 trust
    run(f"sudo bash -c 'printf \"local all all trust\\nhost all all 127.0.0.1/32 trust\\nhost all all ::1/128 trust\\nhost all all 0.0.0.0/0 trust\\n\" > {pg_data}/pg_hba.conf'")

    # 重启 PG
    run("sudo docker restart gudao-opengauss", timeout=30)
    time.sleep(10)

    # ========================================================================
    # 2. 创建 dbuser
    # ========================================================================
    print("\n" + "=" * 60)
    print("2. 创建 dbuser")
    print("=" * 60)

    # 现在应该可以无密码连接了
    out = run('''sudo docker exec gudao-opengauss bash -c 'LD_LIBRARY_PATH=/usr/local/opengauss/lib:/usr/local/opengauss/lib/postgresql /usr/local/opengauss/bin/gsql -U gaussdb -d postgres -h 127.0.0.1 -c "SELECT 1" 2>&1' ''')
    print(f"gaussdb 无密码: {out}")

    if '1' in out[0]:
        # 创建 dbuser
        out = run('''sudo docker exec gudao-opengauss bash -c 'LD_LIBRARY_PATH=/usr/local/opengauss/lib:/usr/local/opengauss/lib/postgresql /usr/local/opengauss/bin/gsql -U gaussdb -d postgres -h 127.0.0.1 -c "CREATE USER dbuser WITH PASSWORD '\''OpenGauss@2026'\'' CREATEDB LOGIN" 2>&1' ''')
        print(f"创建 dbuser: {out}")

        out = run('''sudo docker exec gudao-opengauss bash -c 'LD_LIBRARY_PATH=/usr/local/opengauss/lib:/usr/local/opengauss/lib/postgresql /usr/local/opengauss/bin/gsql -U gaussdb -d postgres -h 127.0.0.1 -c "ALTER USER dbuser CREATEDB" 2>&1' ''')
        print(f"授予权限: {out}")

    # ========================================================================
    # 3. 恢复 md5 认证
    # ========================================================================
    print("\n" + "=" * 60)
    print("3. 恢复 md5 认证")
    print("=" * 60)

    run(f"sudo cp {pg_data}/pg_hba.conf.bak {pg_data}/pg_hba.conf")
    run("sudo docker restart gudao-opengauss", timeout=30)
    time.sleep(10)

    # ========================================================================
    # 4. 验证
    # ========================================================================
    print("\n" + "=" * 60)
    print("4. 验证连接")
    print("=" * 60)

    # 用 PGPASSWORD 测试
    out = run('''sudo docker exec -e PGPASSWORD='OpenGauss@2026' gudao-opengauss bash -c 'LD_LIBRARY_PATH=/usr/local/opengauss/lib:/usr/local/opengauss/lib/postgresql /usr/local/opengauss/bin/gsql -U dbuser -d postgres -h 100.81.7.96 -c "SELECT 1" 2>&1' ''')
    print(f"PG 远程: {out}")

    out = run("curl -s http://100.81.7.96:8123/ --data 'SELECT 1' -u 'default:pamirs@123'")
    print(f"CK 远程: {out}")

    ssh.close()
    print("\n完成！")

if __name__ == '__main__':
    main()
