#!/usr/bin/env python3
"""创建 dbuser - 文件传递密码"""
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

    # 先把密码写入文件
    run("echo 'GudaoBackup_2026!' > /tmp/.pgpass")
    run("echo 'OpenGauss@2026' > /tmp/.pgpass_dbuser")

    def gsql(sql, user="gaussdb", passfile="/tmp/.pgpass", host="127.0.0.1"):
        """用文件方式传递密码"""
        cmd = f'''sudo docker exec gudao-opengauss bash -c 'cat {passfile} | LD_LIBRARY_PATH=/usr/local/opengauss/lib:/usr/local/opengauss/lib/postgresql /usr/local/opengauss/bin/gsql -U {user} -d postgres -h {host} -c "{sql}" 2>&1' '''
        return run(cmd)

    print("=" * 60)
    print("1. 创建 dbuser")
    print("=" * 60)
    out, err = gsql("CREATE USER dbuser WITH PASSWORD 'OpenGauss@2026' CREATEDB LOGIN")
    print(f"结果: {out}")

    print("\n" + "=" * 60)
    print("2. 验证 dbuser 本地")
    print("=" * 60)
    out, err = gsql("SELECT 1", user="dbuser", passfile="/tmp/.pgpass_dbuser")
    print(f"结果: {out}")

    print("\n" + "=" * 60)
    print("3. 验证 dbuser 远程")
    print("=" * 60)
    out, err = gsql("SELECT 1", user="dbuser", passfile="/tmp/.pgpass_dbuser", host="100.81.7.96")
    print(f"结果: {out}")

    print("\n" + "=" * 60)
    print("4. 验证 CK 远程")
    print("=" * 60)
    out, err = run("curl -s http://100.81.7.96:8123/ --data 'SELECT 1' -u 'default:pamirs@123'")
    print(f"结果: {out}")

    ssh.close()
    print("\n完成！")


if __name__ == '__main__':
    main()
