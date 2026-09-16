#!/usr/bin/env python3
"""修复远程数据库 - 第十七轮：用 su 切换用户连接"""
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
    # 1. 用 su - opengauss 连接
    # ========================================================================
    print("=" * 60)
    print("1. 用 su - opengauss 连接")
    print("=" * 60)

    out = run('''sudo docker exec gudao-opengauss bash -c 'su - opengauss -c "gsql -d postgres -c \\\"SELECT 1\\\""' ''')
    print(f"su opengauss: {out}")

    # ========================================================================
    # 2. 创建 dbuser
    # ========================================================================
    print("\n" + "=" * 60)
    print("2. 创建 dbuser")
    print("=" * 60)

    if '1' in out[0]:
        out = run('''sudo docker exec gudao-opengauss bash -c 'su - opengauss -c "gsql -d postgres -c \\\"CREATE USER dbuser WITH PASSWORD '"'"'OpenGauss@2026'"'"' CREATEDB LOGIN\\\""' ''')
        print(f"创建 dbuser: {out}")

    # ========================================================================
    # 3. 验证
    # ========================================================================
    print("\n" + "=" * 60)
    print("3. 验证连接")
    print("=" * 60)

    out = run('''sudo docker exec -e PGPASSWORD='OpenGauss@2026' gudao-opengauss bash -c 'su - opengauss -c "gsql -U dbuser -d postgres -h 100.81.7.96 -c \\\"SELECT 1\\\""' ''')
    print(f"PG 远程: {out}")

    out = run("curl -s http://100.81.7.96:8123/ --data 'SELECT 1' -u 'default:pamirs@123'")
    print(f"CK 远程: {out}")

    ssh.close()
    print("\n完成！")

if __name__ == '__main__':
    main()
