#!/usr/bin/env python3
"""验证 gaussdb 密码和 dbuser 状态"""
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

    # 检查密码文件内容
    print("=" * 60)
    print("1. 检查密码文件")
    print("=" * 60)
    out, err = run("cat /tmp/.pgpass")
    print(f"pgpass: [{out}]")

    # 用 base64 编码密码避免 shell 解释
    print("\n" + "=" * 60)
    print("2. 用 base64 方式")
    print("=" * 60)

    import base64
    pwd = "GudaoBackup_2026!"
    pwd_b64 = base64.b64encode(pwd.encode()).decode()

    # 解码并传递给 gsql
    cmd = f'''sudo docker exec gudao-opengauss bash -c 'echo "{pwd_b64}" | base64 -d | LD_LIBRARY_PATH=/usr/local/opengauss/lib:/usr/local/opengauss/lib/postgresql /usr/local/opengauss/bin/gsql -U gaussdb -d postgres -h 127.0.0.1 -c "SELECT 1" 2>&1' '''
    out, err = run(cmd)
    print(f"gaussdb SELECT 1: {out}")

    # 检查 dbuser
    cmd = f'''sudo docker exec gudao-opengauss bash -c 'echo "{pwd_b64}" | base64 -d | LD_LIBRARY_PATH=/usr/local/opengauss/lib:/usr/local/opengauss/lib/postgresql /usr/local/opengauss/bin/gsql -U gaussdb -d postgres -h 127.0.0.1 -c "SELECT usename FROM pg_user" 2>&1' '''
    out, err = run(cmd)
    print(f"所有用户: {out}")

    ssh.close()


if __name__ == '__main__':
    main()
