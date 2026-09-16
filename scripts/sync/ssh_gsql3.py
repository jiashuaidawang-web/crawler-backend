#!/usr/bin/env python3
"""在远程服务器上创建辅助脚本来执行 gsql"""
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

    # 创建辅助脚本
    helper_script = '''#!/usr/bin/env python3
import subprocess
import os
import sys

def gsql(sql, user="gaussdb", password="GudaoBackup_2026!", db="postgres", host="127.0.0.1"):
    """执行 gsql 命令"""
    env = os.environ.copy()
    env['LD_LIBRARY_PATH'] = '/usr/local/opengauss/lib:/usr/local/opengauss/lib/postgresql'

    cmd = [
        'sudo', 'docker', 'exec', '-i', 'gudao-opengauss', 'bash', '-c',
        f'LD_LIBRARY_PATH=/usr/local/opengauss/lib:/usr/local/opengauss/lib/postgresql /usr/local/opengauss/bin/gsql -U {user} -d {db} -h {host}'
    ]

    proc = subprocess.Popen(
        cmd,
        stdin=subprocess.PIPE,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        env=env
    )

    # 发送密码 + SQL
    input_data = f"{password}\\n{sql}\\n\\\\q\\n"
    stdout, stderr = proc.communicate(input=input_data.encode(), timeout=30)

    return stdout.decode(), stderr.decode()

if __name__ == "__main__":
    sql = sys.argv[1] if len(sys.argv) > 1 else "SELECT 1"
    user = sys.argv[2] if len(sys.argv) > 2 else "gaussdb"
    password = sys.argv[3] if len(sys.argv) > 3 else "GudaoBackup_2026!"
    host = sys.argv[4] if len(sys.argv) > 4 else "127.0.0.1"

    out, err = gsql(sql, user, password, host=host)
    print(out)
    if err:
        print(f"STDERR: {err}", file=sys.stderr)
'''

    # 写入远程服务器
    import base64
    script_b64 = base64.b64encode(helper_script.encode()).decode()
    run(f"echo {script_b64} | base64 -d > /tmp/gsql_helper.py")

    print("=" * 60)
    print("1. 测试 gaussdb 连接")
    print("=" * 60)
    out, err = run("python3 /tmp/gsql_helper.py 'SELECT 1'")
    print(f"结果: {out} {err}")

    print("\n" + "=" * 60)
    print("2. 创建 dbuser")
    print("=" * 60)
    out, err = run("python3 /tmp/gsql_helper.py \"CREATE USER dbuser WITH PASSWORD 'OpenGauss@2026' CREATEDB LOGIN\"")
    print(f"结果: {out} {err}")

    print("\n" + "=" * 60)
    print("3. 验证 dbuser 远程")
    print("=" * 60)
    out, err = run("python3 /tmp/gsql_helper.py 'SELECT 1' dbuser OpenGauss@2026 100.81.7.96")
    print(f"结果: {out} {err}")

    ssh.close()
    print("\n完成！")


if __name__ == '__main__':
    main()
