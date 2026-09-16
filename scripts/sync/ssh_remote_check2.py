#!/usr/bin/env python3
"""远程服务器权限检查"""
import paramiko

def main():
    ssh = paramiko.SSHClient()
    ssh.set_missing_host_key_policy(paramiko.AutoAddPolicy())
    ssh.connect('100.81.7.96', 22, 'box', '3Uz9wVIZ2mpPDoPG', timeout=15, allow_agent=False, look_for_keys=False)

    commands = {
        'whoami': 'whoami',
        'groups': 'groups',
        'docker sock ls': 'ls -la /var/run/docker.sock 2>&1',
        'docker ps with sudo': 'sudo docker ps -a --format "{{.Names}} {{.Status}} {{.Ports}}" 2>&1',
        'docker images with sudo': 'sudo docker images --format "{{.Repository}}:{{.Tag}}" 2>&1',
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
