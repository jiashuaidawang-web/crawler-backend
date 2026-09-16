#!/usr/bin/env python3
"""修复远程数据库 - 第九轮：创建 dbuser，初始化 PG 数据库"""
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

    def gsql(cmd, user="gaussdb", db="postgres"):
        """执行 gsql 命令"""
        full_cmd = f"""sudo docker exec gudao-opengauss bash -c 'echo "GudaoBackup_2026!" | LD_LIBRARY_PATH=/usr/local/opengauss/lib:/usr/local/opengauss/lib/postgresql /usr/local/opengauss/bin/gsql -U {user} -d {db} -h 127.0.0.1 -c "{cmd}" 2>&1'"""
        return run(full_cmd, timeout=30)

    # ========================================================================
    # 1. 检查并创建 dbuser
    # ========================================================================
    print("=" * 60)
    print("1. 创建 dbuser")
    print("=" * 60)

    # 检查 dbuser 是否存在
    out, err = gsql("SELECT usename FROM pg_user WHERE usename='dbuser'")
    print(f"检查 dbuser: {out}")

    if 'dbuser' not in out:
        # 创建 dbuser
        out, err = gsql("CREATE USER dbuser WITH PASSWORD 'OpenGauss@2026' CREATEDB LOGIN")
        print(f"创建 dbuser: [{out}] [{err}]")
    else:
        # 修改密码
        out, err = gsql("ALTER USER dbuser WITH PASSWORD 'OpenGauss@2026'")
        print(f"修改 dbuser 密码: [{out}] [{err}]")

    # 验证 dbuser 连接
    out, err = run("""sudo docker exec gudao-opengauss bash -c 'echo "OpenGauss@2026" | LD_LIBRARY_PATH=/usr/local/opengauss/lib:/usr/local/opengauss/lib/postgresql /usr/local/opengauss/bin/gsql -U dbuser -d postgres -h 127.0.0.1 -c "SELECT 1" 2>&1'""")
    print(f"dbuser 连接: [{out}] [{err}]")

    # ========================================================================
    # 2. 恢复 pg_hba.conf（改回 md5/sha256）
    # ========================================================================
    print("\n" + "=" * 60)
    print("2. 恢复 pg_hba.conf")
    print("=" * 60)

    # 找到 PG data 目录
    out, err = run("sudo docker inspect gudao-opengauss --format '{{range .Mounts}}{{if eq .Destination \"/var/lib/opengauss/data\"}}{{.Source}}{{end}}{{end}}'")
    pg_data = out.strip()
    print(f"PG data: {pg_data}")

    if pg_data:
        # 恢复为 md5 认证（更安全）
        run(f"sudo sed -i 's/host all all 0.0.0.0\\/0 trust/host all all 0.0.0.0\\/0 md5/' {pg_data}/pg_hba.conf")
        # 确保 TailScale 网段是 md5
        run(f"sudo sed -i 's/host    all             all             100.81.0.0\\/16           trust/host    all             all             100.81.0.0\\/16           md5/' {pg_data}/pg_hba.conf")

        # 重新加载配置
        run("sudo docker restart gudao-opengauss", timeout=30)
        time.sleep(10)

    # ========================================================================
    # 3. 验证远程连接
    # ========================================================================
    print("\n" + "=" * 60)
    print("3. 验证远程连接")
    print("=" * 60)

    # 从本机测试远程 PG（使用 Python）
    out, err = run("""sudo docker exec gudao-opengauss bash -c 'echo "OpenGauss@2026" | LD_LIBRARY_PATH=/usr/local/opengauss/lib:/usr/local/opengauss/lib/postgresql /usr/local/opengauss/bin/gsql -U dbuser -d postgres -h 100.81.7.96 -c "SELECT 1" 2>&1'""")
    print(f"PG 远程连接 (100.81.7.96): [{out}] [{err}]")

    # CK 远程连接
    out, err = run("curl -s http://100.81.7.96:8123/ --data 'SELECT 1' -u 'default:pamirs@123'")
    print(f"CK 远程连接: [{out}]")

    ssh.close()
    print("\n修复完成！")

if __name__ == '__main__':
    main()
