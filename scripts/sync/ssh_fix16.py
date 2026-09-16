#!/usr/bin/env python3
"""修复远程数据库 - 第十六轮：检查日志，尝试 socket 连接"""
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
    # 1. 检查 PG 日志
    # ========================================================================
    print("=" * 60)
    print("1. 检查 PG 日志")
    print("=" * 60)

    out = run("sudo docker logs --tail 30 gudao-opengauss 2>&1")
    print(f"PG 日志:\n{out[0][:2000]}")

    # ========================================================================
    # 2. 检查 pg_hba.conf 实际位置
    # ========================================================================
    print("\n" + "=" * 60)
    print("2. 检查配置文件位置")
    print("=" * 60)

    out = run("sudo docker exec gudao-opengauss bash -c 'ls -la /var/lib/opengauss/data/pg_hba.conf'")
    print(f"pg_hba.conf: {out}")

    out = run("sudo docker exec gudao-opengauss bash -c 'cat /var/lib/opengauss/data/pg_hba.conf | head -10'")
    print(f"容器内 pg_hba.conf:\n{out[0]}")

    # ========================================================================
    # 3. 尝试 Unix socket 连接（不用 -h）
    # ========================================================================
    print("\n" + "=" * 60)
    print("3. 尝试 Unix socket 连接")
    print("=" * 60)

    out = run('''sudo docker exec gudao-opengauss bash -c 'LD_LIBRARY_PATH=/usr/local/opengauss/lib:/usr/local/opengauss/lib/postgresql /usr/local/opengauss/bin/gsql -U gaussdb -d postgres -c "SELECT 1" 2>&1' ''')
    print(f"gaussdb socket: {out}")

    # ========================================================================
    # 4. 用 expect 方式自动输入密码
    # ========================================================================
    print("\n" + "=" * 60)
    print("4. 用 GudaoBackup_2026! 连接")
    print("=" * 60)

    # 用 expect 方式
    out = run('''sudo docker exec gudao-opengauss bash -c 'expect -c "
spawn /usr/local/opengauss/bin/gsql -U gaussdb -d postgres -h 127.0.0.1
expect \\\"Password for user gaussdb:\\\"
send \\\"GudaoBackup_2026!\\\r\\\"
expect \\\"postgres=>\\\"
send \\\"SELECT 1;\\\r\\\"
expect \\\"postgres=>\\\"
send \\\"\\\\q\\\r\\\"
expect eof
" 2>&1' ''')
    print(f"expect 方式: {out}")

    ssh.close()

if __name__ == '__main__':
    main()
