#!/usr/bin/env python3
"""用 invoke_shell 执行 gsql"""
import paramiko
import time

def main():
    ssh = paramiko.SSHClient()
    ssh.set_missing_host_key_policy(paramiko.AutoAddPolicy())
    ssh.connect('100.81.7.96', 22, 'box', '3Uz9wVIZ2mpPDoPG', timeout=15, allow_agent=False, look_for_keys=False)

    # 使用 invoke_shell 获取交互式 shell
    channel = ssh.invoke_shell(term='xterm', width=200, height=50)
    time.sleep(2)

    def send(cmd, wait=2):
        channel.send(cmd + "\n")
        time.sleep(wait)
        output = ""
        while channel.recv_ready():
            output += channel.recv(4096).decode('utf-8', errors='replace')
        return output

    # 先测试基本命令
    print("=" * 60)
    print("1. 基本命令测试")
    print("=" * 60)
    out = send("hostname")
    print(out)

    # 执行 gsql
    print("=" * 60)
    print("2. 执行 gsql")
    print("=" * 60)

    gsql_cmd = 'sudo docker exec gudao-opengauss bash -c \'LD_LIBRARY_PATH=/usr/local/opengauss/lib:/usr/local/opengauss/lib/postgresql /usr/local/opengauss/bin/gsql -U gaussdb -d postgres -h 127.0.0.1\''
    out = send(gsql_cmd, wait=3)
    print(f"发送 gsql 命令后:\n{out}")

    # 发送密码
    if 'Password' in out or 'password' in out:
        out = send("GudaoBackup_2026!", wait=2)
        print(f"发送密码后:\n{out}")

    # 执行 SQL
    out = send("SELECT 1;", wait=2)
    print(f"执行 SQL:\n{out}")

    # 创建用户
    out = send("CREATE USER dbuser WITH PASSWORD 'OpenGauss@2026' CREATEDB LOGIN;", wait=2)
    print(f"创建用户:\n{out}")

    # 退出
    out = send("\\q", wait=1)
    print(f"退出:\n{out}")

    channel.close()
    ssh.close()
    print("\n完成！")


if __name__ == '__main__':
    main()
