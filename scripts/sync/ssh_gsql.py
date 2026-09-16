#!/usr/bin/env python3
"""用 paramiko PTY 自动执行 gsql 命令"""
import paramiko
import time
import re

class GsqlExecutor:
    def __init__(self, ssh_client):
        self.ssh = ssh_client

    def exec_sql(self, sql, user="gaussdb", password="GudaoBackup_2026!", db="postgres", host="127.0.0.1", timeout=30):
        """执行 SQL 并返回结果"""
        transport = self.ssh.get_transport()
        channel = transport.open_session()
        channel.get_pty(term='xterm')
        channel.settimeout(timeout)

        # 构建 gsql 命令
        gsql_path = '/usr/local/opengauss/bin/gsql'
        ld_path = '/usr/local/opengauss/lib:/usr/local/opengauss/lib/postgresql'
        cmd = f'LD_LIBRARY_PATH={ld_path} {gsql_path} -U {user} -d {db} -h {host} -c "{sql}"'

        channel.exec_command(f'sudo docker exec gudao-opengauss bash -c \'{cmd}\'')

        output = b""
        password_sent = False
        start_time = time.time()

        while time.time() - start_time < timeout:
            if channel.recv_ready():
                data = channel.recv(4096)
                output += data
                text = data.decode('utf-8', errors='replace')

                # 检测到密码提示，发送密码
                if not password_sent and ('Password' in text or 'password' in text):
                    time.sleep(0.3)
                    channel.send(password + "\r")
                    password_sent = True
                    time.sleep(1)

            if channel.exit_status_ready():
                break
            time.sleep(0.2)

        # 读取剩余输出
        try:
            while channel.recv_ready():
                output += channel.recv(4096)
        except:
            pass

        channel.close()
        return output.decode('utf-8', errors='replace')


def main():
    ssh = paramiko.SSHClient()
    ssh.set_missing_host_key_policy(paramiko.AutoAddPolicy())
    ssh.connect('100.81.7.96', 22, 'box', '3Uz9wVIZ2mpPDoPG', timeout=15, allow_agent=False, look_for_keys=False)

    gsql = GsqlExecutor(ssh)

    print("=" * 60)
    print("1. 测试 gaussdb 连接")
    print("=" * 60)
    result = gsql.exec_sql("SELECT 1")
    print(f"结果:\n{result}")

    print("\n" + "=" * 60)
    print("2. 创建 dbuser")
    print("=" * 60)
    result = gsql.exec_sql("CREATE USER dbuser WITH PASSWORD 'OpenGauss@2026' CREATEDB LOGIN")
    print(f"结果:\n{result}")

    print("\n" + "=" * 60)
    print("3. 验证 dbuser 远程连接")
    print("=" * 60)
    result = gsql.exec_sql("SELECT 1", user="dbuser", password="OpenGauss@2026", host="100.81.7.96")
    print(f"结果:\n{result}")

    ssh.close()
    print("\n完成！")


if __name__ == '__main__':
    main()
