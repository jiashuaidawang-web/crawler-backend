#!/usr/bin/env python3
"""验证远程数据库状态（修正引号）"""
import paramiko
import time
import base64

def main():
    ssh = paramiko.SSHClient()
    ssh.set_missing_host_key_policy(paramiko.AutoAddPolicy())
    ssh.connect('100.81.7.96', 22, 'box', '3Uz9wVIZ2mpPDoPG', timeout=15, allow_agent=False, look_for_keys=False)

    def run(cmd, timeout=60):
        stdin, stdout, stderr = ssh.exec_command(cmd, timeout=timeout)
        out = stdout.read().decode().strip()
        err = stderr.read().decode().strip()
        return out, err

    gaussdb_pwd = base64.b64encode("GudaoBackup_2026!".encode()).decode()
    dbuser_pwd = base64.b64encode("OpenGauss@2026".encode()).decode()

    def gsql(sql, user="gaussdb", pwd_b64=gaussdb_pwd, host="127.0.0.1"):
        # 将 SQL 写入文件，避免 shell 引号问题
        sql_b64 = base64.b64encode(sql.encode()).decode()
        run(f"echo {sql_b64} | base64 -d > /tmp/query.sql")
        run("sudo docker cp /tmp/query.sql gudao-opengauss:/tmp/query.sql")
        cmd = f'''sudo docker exec gudao-opengauss bash -c 'echo "{pwd_b64}" | base64 -d | LD_LIBRARY_PATH=/usr/local/opengauss/lib:/usr/local/opengauss/lib/postgresql /usr/local/opengauss/bin/gsql -U {user} -d postgres -h {host} -f /tmp/query.sql 2>&1' '''
        return run(cmd)

    # ========================================================================
    # 1. ClickHouse: 列出所有表
    # ========================================================================
    print("=" * 60)
    print("1. ClickHouse 表")
    print("=" * 60)
    out, err = run("curl -s http://100.81.7.96:8123/ --data 'SHOW TABLES FROM crawler' -u 'default:pamirs@123'")
    ck_tables = [t for t in out.split('\n') if t.strip()]
    print(f"共 {len(ck_tables)} 个表:")
    for t in ck_tables:
        print(f"  {t}")

    # ========================================================================
    # 2. openGauss: 列出所有表
    # ========================================================================
    print("\n" + "=" * 60)
    print("2. openGauss 表 (gaussdb)")
    print("=" * 60)
    out, err = gsql("SELECT schemaname, tablename FROM pg_tables WHERE schemaname = 'public' ORDER BY tablename;")
    print(out)

    # ========================================================================
    # 3. openGauss: 所有 schema 的表
    # ========================================================================
    print("\n" + "=" * 60)
    print("3. openGauss 所有 schema")
    print("=" * 60)
    out, err = gsql("SELECT schemaname, count(*) FROM pg_tables GROUP BY schemaname ORDER BY schemaname;")
    print(out)

    # ========================================================================
    # 4. 检查 dbuser 权限
    # ========================================================================
    print("\n" + "=" * 60)
    print("4. dbuser 权限")
    print("=" * 60)
    out, err = gsql("SELECT usename, usesysid, usecreatedb, userepl FROM pg_user WHERE usename = 'dbuser';")
    print(out)

    # ========================================================================
    # 5. 检查表所有者
    # ========================================================================
    print("\n" + "=" * 60)
    print("5. 表所有者")
    print("=" * 60)
    out, err = gsql("SELECT tableowner, count(*) FROM pg_tables GROUP BY tableowner;")
    print(out)

    # ========================================================================
    # 6. dbuser 远程查询
    # ========================================================================
    print("\n" + "=" * 60)
    print("6. dbuser 远程查询")
    print("=" * 60)
    out, err = gsql("SELECT schemaname, tablename FROM pg_tables WHERE schemaname = 'public' ORDER BY tablename;",
                    user="dbuser", pwd_b64=dbuser_pwd, host="100.81.7.96")
    print(out)

    # ========================================================================
    # 7. 检查 stock_daily 表是否存在
    # ========================================================================
    print("\n" + "=" * 60)
    print("7. 检查特定表")
    print("=" * 60)
    out, err = gsql("SELECT count(*) FROM information_schema.columns WHERE table_name = 'stock_daily';")
    print(f"stock_daily columns: {out}")

    ssh.close()
    print("\n完成！")


if __name__ == '__main__':
    main()
