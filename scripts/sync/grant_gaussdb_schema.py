#!/usr/bin/env python3
"""授权 dbuser 访问 gaussdb schema 中的表"""
import paramiko
import time
import base64

def main():
    ssh = paramiko.SSHClient()
    ssh.set_missing_host_key_policy(paramiko.AutoAddPolicy())
    ssh.connect('100.81.7.96', 22, 'box', '3Uz9wVIZ2mpPDoPG', timeout=15, allow_agent=False, look_for_keys=False)

    def run(cmd, timeout=120):
        stdin, stdout, stderr = ssh.exec_command(cmd, timeout=timeout)
        out = stdout.read().decode().strip()
        err = stderr.read().decode().strip()
        return out, err

    gaussdb_pwd = base64.b64encode("GudaoBackup_2026!".encode()).decode()

    def gsql(sql, user="gaussdb", pwd_b64=gaussdb_pwd, host="127.0.0.1"):
        sql_b64 = base64.b64encode(sql.encode()).decode()
        run(f"echo {sql_b64} | base64 -d > /tmp/query.sql")
        run("sudo docker cp /tmp/query.sql gudao-opengauss:/tmp/query.sql")
        cmd = f'''sudo docker exec gudao-opengauss bash -c 'echo "{pwd_b64}" | base64 -d | LD_LIBRARY_PATH=/usr/local/opengauss/lib:/usr/local/opengauss/lib/postgresql /usr/local/opengauss/bin/gsql -U {user} -d postgres -h {host} -f /tmp/query.sql 2>&1' '''
        return run(cmd)

    # ========================================================================
    # 1. 授权 dbuser 访问 gaussdb schema
    # ========================================================================
    print("=" * 60)
    print("1. 授权 dbuser 访问 gaussdb schema")
    print("=" * 60)

    grant_sql = """
    -- 授权使用 gaussdb schema
    GRANT USAGE ON SCHEMA gaussdb TO dbuser;

    -- 授权所有表
    GRANT ALL ON ALL TABLES IN SCHEMA gaussdb TO dbuser;

    -- 授权所有序列
    GRANT ALL ON ALL SEQUENCES IN SCHEMA gaussdb TO dbuser;

    -- 默认权限
    ALTER DEFAULT PRIVILEGES IN SCHEMA gaussdb GRANT ALL ON TABLES TO dbuser;
    ALTER DEFAULT PRIVILEGES IN SCHEMA gaussdb GRANT ALL ON SEQUENCES TO dbuser;
    ALTER DEFAULT PRIVILEGES FOR USER gaussdb IN SCHEMA gaussdb GRANT ALL ON TABLES TO dbuser;
    ALTER DEFAULT PRIVILEGES FOR USER gaussdb IN SCHEMA gaussdb GRANT ALL ON SEQUENCES TO dbuser;
    """

    grant_b64 = base64.b64encode(grant_sql.encode()).decode()
    run(f"echo {grant_b64} | base64 -d > /tmp/grant.sql")
    run("sudo docker cp /tmp/grant.sql gudao-opengauss:/tmp/grant.sql")

    cmd = f'''sudo docker exec gudao-opengauss bash -c 'echo "{gaussdb_pwd}" | base64 -d | LD_LIBRARY_PATH=/usr/local/opengauss/lib:/usr/local/opengauss/lib/postgresql /usr/local/opengauss/bin/gsql -U gaussdb -d postgres -h 127.0.0.1 -f /tmp/grant.sql 2>&1' '''
    out, err = run(cmd)
    print(out)

    # ========================================================================
    # 2. 设置 dbuser 的 search_path
    # ========================================================================
    print("\n" + "=" * 60)
    print("2. 设置 dbuser search_path")
    print("=" * 60)

    out, err = gsql("ALTER USER dbuser SET search_path = public, gaussdb;")
    print(out)

    # ========================================================================
    # 3. dbuser 远程验证
    # ========================================================================
    print("\n" + "=" * 60)
    print("3. dbuser 远程验证")
    print("=" * 60)

    dbuser_pwd = base64.b64encode("OpenGauss@2026".encode()).decode()

    # 查询 public + gaussdb schema 的表
    sql = "SELECT schemaname, count(*) FROM pg_tables WHERE schemaname IN ('public', 'gaussdb') GROUP BY schemaname;"
    sql_b64 = base64.b64encode(sql.encode()).decode()
    run(f"echo {sql_b64} | base64 -d > /tmp/query.sql")
    run("sudo docker cp /tmp/query.sql gudao-opengauss:/tmp/query.sql")

    cmd = f'''sudo docker exec gudao-opengauss bash -c 'echo "{dbuser_pwd}" | base64 -d | LD_LIBRARY_PATH=/usr/local/opengauss/lib:/usr/local/opengauss/lib/postgresql /usr/local/opengauss/bin/gsql -U dbuser -d postgres -h 100.81.7.96 -f /tmp/query.sql 2>&1' '''
    out, err = run(cmd)
    print(f"远程表统计:\n{out}")

    # ========================================================================
    # 4. 列出所有可访问的表
    # ========================================================================
    print("\n" + "=" * 60)
    print("4. dbuser 可访问的表")
    print("=" * 60)

    sql = "SELECT schemaname, tablename FROM pg_tables WHERE schemaname IN ('public', 'gaussdb') ORDER BY schemaname, tablename;"
    sql_b64 = base64.b64encode(sql.encode()).decode()
    run(f"echo {sql_b64} | base64 -d > /tmp/query.sql")
    run("sudo docker cp /tmp/query.sql gudao-opengauss:/tmp/query.sql")

    cmd = f'''sudo docker exec gudao-opengauss bash -c 'echo "{dbuser_pwd}" | base64 -d | LD_LIBRARY_PATH=/usr/local/opengauss/lib:/usr/local/opengauss/lib/postgresql /usr/local/opengauss/bin/gsql -U dbuser -d postgres -h 100.81.7.96 -f /tmp/query.sql 2>&1' '''
    out, err = run(cmd)
    print(out)

    # ========================================================================
    # 5. 测试查询
    # ========================================================================
    print("\n" + "=" * 60)
    print("5. 测试查询 stock_daily")
    print("=" * 60)

    sql = "SELECT count(*) FROM stock_daily;"
    sql_b64 = base64.b64encode(sql.encode()).decode()
    run(f"echo {sql_b64} | base64 -d > /tmp/query.sql")
    run("sudo docker cp /tmp/query.sql gudao-opengauss:/tmp/query.sql")

    cmd = f'''sudo docker exec gudao-opengauss bash -c 'echo "{dbuser_pwd}" | base64 -d | LD_LIBRARY_PATH=/usr/local/opengauss/lib:/usr/local/opengauss/lib/postgresql /usr/local/opengauss/bin/gsql -U dbuser -d postgres -h 100.81.7.96 -f /tmp/query.sql 2>&1' '''
    out, err = run(cmd)
    print(out)

    ssh.close()
    print("\n完成！")


if __name__ == '__main__':
    main()
