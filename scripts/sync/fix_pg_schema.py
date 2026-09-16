#!/usr/bin/env python3
"""修复 openGauss 表 schema：将表从 gaussdb 移到 public"""
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
    # 1. 列出 gaussdb schema 中的表
    # ========================================================================
    print("=" * 60)
    print("1. gaussdb schema 中的表")
    print("=" * 60)
    out, err = gsql("SELECT tablename FROM pg_tables WHERE schemaname = 'gaussdb' ORDER BY tablename;")
    print(out)

    # ========================================================================
    # 2. 生成迁移 SQL：将表移到 public schema
    # ========================================================================
    print("\n" + "=" * 60)
    print("2. 迁移表到 public schema")
    print("=" * 60)

    # 获取表列表
    tables = [t.strip() for t in out.split('\n') if t.strip() and not t.startswith('tablename') and not t.startswith('---')]

    if not tables:
        print("没有需要迁移的表")
    else:
        # 生成迁移 SQL
        migrate_sql = ""
        for t in tables:
            migrate_sql += f'ALTER TABLE gaussdb."{t}" SET SCHEMA public;\n'

        # 写入文件并执行
        migrate_b64 = base64.b64encode(migrate_sql.encode()).decode()
        run(f"echo {migrate_b64} | base64 -d > /tmp/migrate.sql")
        run("sudo docker cp /tmp/migrate.sql gudao-opengauss:/tmp/migrate.sql")

        cmd = f'''sudo docker exec gudao-opengauss bash -c 'echo "{gaussdb_pwd}" | base64 -d | LD_LIBRARY_PATH=/usr/local/opengauss/lib:/usr/local/opengauss/lib/postgresql /usr/local/opengauss/bin/gsql -U gaussdb -d postgres -h 127.0.0.1 -f /tmp/migrate.sql 2>&1' '''
        out, err = run(cmd)
        print(out)

    # ========================================================================
    # 3. 验证 public schema
    # ========================================================================
    print("\n" + "=" * 60)
    print("3. public schema 表")
    print("=" * 60)
    out, err = gsql("SELECT tablename FROM pg_tables WHERE schemaname = 'public' ORDER BY tablename;")
    print(out)

    # ========================================================================
    # 4. 授权 dbuser 访问 public schema
    # ========================================================================
    print("\n" + "=" * 60)
    print("4. 授权 dbuser")
    print("=" * 60)
    grant_sql = """
    GRANT ALL ON SCHEMA public TO dbuser;
    GRANT ALL ON ALL TABLES IN SCHEMA public TO dbuser;
    GRANT ALL ON ALL SEQUENCES IN SCHEMA public TO dbuser;
    ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT ALL ON TABLES TO dbuser;
    ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT ALL ON SEQUENCES TO dbuser;
    """
    grant_b64 = base64.b64encode(grant_sql.encode()).decode()
    run(f"echo {grant_b64} | base64 -d > /tmp/grant.sql")
    run("sudo docker cp /tmp/grant.sql gudao-opengauss:/tmp/grant.sql")

    cmd = f'''sudo docker exec gudao-opengauss bash -c 'echo "{gaussdb_pwd}" | base64 -d | LD_LIBRARY_PATH=/usr/local/opengauss/lib:/usr/local/opengauss/lib/postgresql /usr/local/opengauss/bin/gsql -U gaussdb -d postgres -h 127.0.0.1 -f /tmp/grant.sql 2>&1' '''
    out, err = run(cmd)
    print(out)

    # ========================================================================
    # 5. dbuser 远程验证
    # ========================================================================
    print("\n" + "=" * 60)
    print("5. dbuser 远程验证")
    print("=" * 60)
    dbuser_pwd = base64.b64encode("OpenGauss@2026".encode()).decode()
    cmd = f'''sudo docker exec gudao-opengauss bash -c 'echo "{dbuser_pwd}" | base64 -d | LD_LIBRARY_PATH=/usr/local/opengauss/lib:/usr/local/opengauss/lib/postgresql /usr/local/opengauss/bin/gsql -U dbuser -d postgres -h 100.81.7.96 -c "SELECT count(*) FROM pg_tables WHERE schemaname='"'"'public'"'"';" 2>&1' '''
    out, err = run(cmd)
    print(f"public 表数量: {out}")

    ssh.close()
    print("\n完成！")


if __name__ == '__main__':
    main()
