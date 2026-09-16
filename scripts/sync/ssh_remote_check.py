#!/usr/bin/env python3
"""远程服务器状态检查"""
import sys
import paramiko

def main():
    ssh = paramiko.SSHClient()
    ssh.set_missing_host_key_policy(paramiko.AutoAddPolicy())
    ssh.connect('100.81.7.96', 22, 'box', '3Uz9wVIZ2mpPDoPG', timeout=15, allow_agent=False, look_for_keys=False)

    commands = {
        'hostname': 'hostname',
        'docker version': 'docker version --format "{{.Server.Version}}" 2>&1',
        'docker ps -a': 'docker ps -a --format "{{.Names}} {{.Status}} {{.Ports}}"',
        'docker images': 'docker images --format "{{.Repository}}:{{.Tag}}"',
        'disk space': 'df -h /',
        'memory': 'free -h',
        'list /root': 'ls -la /root/ 2>/dev/null || echo "no /root"',
        'list home': 'ls -la /home/box/ 2>/dev/null || echo "no home"',
    }

    for label, cmd in commands.items():
        stdin, stdout, stderr = ssh.exec_command(cmd, timeout=15)
        output = stdout.read().decode().strip()
        err = stderr.read().decode().strip()
        print(f"\n=== {label} ===")
        print(f"$ {cmd}")
        if output:
            print(output)
        if err:
            print(f"[stderr] {err}")

    ssh.close()

if __name__ == '__main__':
    main()
