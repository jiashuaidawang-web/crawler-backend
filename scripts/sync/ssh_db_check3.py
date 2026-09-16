#!/usr/bin/env python3
"""远程数据库详细检查"""
import paramiko

def main():
    ssh = paramiko.SSHClient()
    ssh.set_missing_host_key_policy(paramiko.AutoAddPolicy())
    ssh.connect('100.81.7.96', 22, 'box', '3Uz9wVIZ2mpPDoPG', timeout=15, allow_agent=False, look_for_keys=False)

    commands = {
        'PG find gsql': 'sudo docker exec gudao-opengauss find / -name "gsql" -o -name "psql" 2>/dev/null | head -10',
        'PG gsql version': 'sudo docker exec gudao-opengauss gsql --version 2>&1',
        'PG gsql test': 'sudo docker exec gudao-opengauss gsql -U dbuser -d postgres -c "SELECT 1" 2>&1',
        'PG databases': 'sudo docker exec gudao-opengauss gsql -U dbuser -d postgres -c "\\l" 2>&1',
        'PG tables': 'sudo docker exec gudao-opengauss gsql -U dbuser -d postgres -c "\\dt" 2>&1',
        'CK create db': 'sudo docker exec gudao-clickhouse clickhouse-client --query "CREATE DATABASE IF NOT EXISTS crawler" 2>&1',
        'CK show listen': 'sudo docker exec gudao-clickhouse cat /etc/clickhouse-server/config.xml 2>&1 | grep -i listen',
    }

    for label, cmd in commands.items():
        stdin, stdout, stderr = ssh.exec_command(cmd, timeout=30)
        output = stdout.read().decode().strip()
        err = stderr.read().decode().strip()
        print(f"\n=== {label} ===")
        if output:
            print(output)
        if err:
            print(f"[stderr] {err}")

    ssh.close()

if __name__ == '__main__':
    main()
