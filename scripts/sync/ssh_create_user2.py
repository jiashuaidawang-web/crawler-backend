#!/usr/bin/env python3
"""创建 dbuser - 改进版"""
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

    # 改进的辅助脚本 - 分别读取 stdout 和 stderr
    helper = '''#!/usr/bin/env python3
import subprocess, os, sys

def gsql(sql, user="gaussdb", password="GudaoBackup_2026!", db="postgres", host="127.0.0.1"):
    """执行 gsql，分别处理密码提示"""
    cmd = ['sudo', 'docker', 'exec', '-i', 'gudao-opengauss', 'bash', '-c',
           f'LD_LIBRARY_PATH=/usr/local/opengauss/lib:/usr/local/opengauss/lib/postgresql /usr/local/opengauss/bin/gsql -U {user} -d {db} -h {host} 2>/dev/null']

    proc = subprocess.Popen(
        cmd,
        stdin=subprocess.PIPE,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE
    )

    # 先发送密码
    proc.stdin.write(f"{password}\\n".encode())
    proc.stdin.flush()

    # 等待一下
    import time
    time.sleep(0.5)

    # 发送 SQL
    proc.stdin.write(f"{sql}\\n".encode())
    proc.stdin.flush()

    time.sleep(0.5)

    # 发送退出
    proc.stdin.write(b"\\\\q\\n")
    proc.stdin.flush()

    stdout, stderr = proc.communicate(timeout=30)
    return stdout.decode(), stderr.decode()

if __name__ == "__main__":
    sql = sys.argv[1] if len(sys.argv) > 1 else "SELECT 1"
    user = sys.argv[2] if len(sys.argv) > 2 else "gaussdb"
    password = sys.argv[3] if len(sys.argv) > 3 else "GudaoBackup_2026!"
    host = sys.argv[4] if len(sys.argv) > 4 else "127.0.0.1"

    out, err = gsql(sql, user, password, host=host)
    print(f"STDOUT: {out}")
    print(f"STDERR: {err}")
'''

    import base64
    run(f"echo {base64.b64encode(helper.encode()).decode()} | base64 -d > /tmp/gsql_helper2.py")

    print("=" * 60)
    print("1. 创建 dbuser")
    print("=" * 60)
    out, err = run("python3 /tmp/gsql_helper2.py \"CREATE USER dbuser WITH PASSWORD 'OpenGauss@2026' CREATEDB LOGIN\"")
    print(out[:500])
    if err:
        print(f"ERR: {err[:200]}")

    print("\n" + "=" * 60)
    print("2. 验证 dbuser 本地")
    print("=" * 60)
    out, err = run("python3 /tmp/gsql_helper2.py 'SELECT 1' dbuser OpenGauss@2026 127.0.0.1")
    print(out[:500])
    if err:
        print(f"ERR: {err[:200]}")

    print("\n" + "=" * 60)
    print("3. 验证 dbuser 远程")
    print("=" * 60)
    out, err = run("python3 /tmp/gsql_helper2.py 'SELECT 1' dbuser OpenGauss@2026 100.81.7.96")
    print(out[:500])
    if err:
        print(f"ERR: {err[:200]}")

    ssh.close()
    print("\n完成！")


if __name__ == '__main__':
    main()
