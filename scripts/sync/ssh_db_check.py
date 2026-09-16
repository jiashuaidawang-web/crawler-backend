#!/usr/bin/env python3
"""远程数据库状态检查"""
import paramiko

def main():
    ssh = paramiko.SSHClient()
    ssh.set_missing_host_key_policy(paramiko.AutoAddPolicy())
    ssh.connect('100.81.7.96', 22, 'box', '3Uz9wVIZ2mpPDoPG', timeout=15, allow_agent=False, look_for_keys=False)

    commands = {
        'CK ping': 'sudo docker exec gudao-clickhouse clickhouse-client --query "SELECT 1" 2>&1',
        'CK databases': 'sudo docker exec gudao-clickhouse clickhouse-client --query "SHOW DATABASES" 2>&1',
        'CK tables': 'sudo docker exec gudao-clickhouse clickhouse-client --query "SHOW TABLES FROM crawler" 2>&1',
        'CK users': 'sudo docker exec gudao-clickhouse clickhouse-client --query "SELECT name FROM system.users" 2>&1',
        'PG ping': 'sudo docker exec gudao-opengauss psql -U dbuser -d postgres -c "SELECT 1" 2>&1',
        'PG databases': 'sudo docker exec gudao-opengauss psql -U dbuser -d postgres -c "\\l" 2>&1',
        'PG tables': 'sudo docker exec gudao-opengauss psql -U dbuser -d postgres -c "\\dt" 2>&1',
        'CK listen': 'sudo docker exec gudao-clickhouse clickhouse-client --query "SELECT * FROM system.settings WHERE name LIKE \'%listen%\'" 2>&1',
    }

    for label, cmd in commands.items():
        stdin, stdout, stderr = ssh.exec_command(cmd, timeout=20)
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
