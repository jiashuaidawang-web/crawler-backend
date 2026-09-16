#!/usr/bin/env python3
"""SSH 连接测试脚本"""
import sys
import paramiko

def test_ssh():
    ssh = paramiko.SSHClient()
    ssh.set_missing_host_key_policy(paramiko.AutoAddPolicy())
    try:
        print("正在连接 100.81.7.96:22 ...")
        ssh.connect(
            hostname='100.81.7.96',
            port=22,
            username='box',
            password='3Uz9wVIZ2mpPDoPG',
            timeout=15,
            allow_agent=False,
            look_for_keys=False
        )
        print("SSH 连接成功!")

        # 执行命令
        commands = [
            'hostname',
            'docker ps --format "{{.Names}} {{.Status}}"',
        ]
        for cmd in commands:
            stdin, stdout, stderr = ssh.exec_command(cmd, timeout=15)
            output = stdout.read().decode().strip()
            print(f"\n$ {cmd}")
            print(output)

        ssh.close()
        return True
    except paramiko.AuthenticationException as e:
        print(f"认证失败: {e}")
        return False
    except paramiko.SSHException as e:
        print(f"SSH 协议异常: {e}")
        return False
    except Exception as e:
        print(f"连接错误: {type(e).__name__}: {e}")
        return False

if __name__ == '__main__':
    success = test_ssh()
    sys.exit(0 if success else 1)
