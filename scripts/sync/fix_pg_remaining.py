#!/usr/bin/env python3
"""修复剩余未迁移的表"""
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
    # 1. 查看 gaussdb schema 剩余的表
    # ========================================================================
    print("=" * 60)
    print("1. gaussdb schema 剩余的表")
    print("=" * 60)
    out, err = gsql("SELECT tablename FROM pg_tables WHERE schemaname = 'gaussdb' ORDER BY tablename;")
    print(out)

    # ========================================================================
    # 2. 查看 public schema 现有的表
    # ========================================================================
    print("\n" + "=" * 60)
    print("2. public schema 现有的表")
    print("=" * 60)
    out, err = gsql("SELECT tablename FROM pg_tables WHERE schemaname = 'public' ORDER BY tablename;")
    print(out)

    # ========================================================================
    # 3. 手动迁移剩余的表（逐个处理）
    # ========================================================================
    print("\n" + "=" * 60)
    print("3. 迁移剩余的表")
    print("=" * 60)

    # 需要迁移的表（根据 schema-full-rebuild.sql 中有 serial/sequence 的表）
    remaining_tables = ["board_basic", "crawl_alert", "crawl_task", "news_event", "stock_board_rel", "trade_log"]

    for table in remaining_tables:
        print(f"\n迁移 {table}...")
        # 先检查表是否还在 gaussdb schema
        out, err = gsql(f"SELECT count(*) FROM pg_tables WHERE schemaname = 'gaussdb' AND tablename = '{table}';")
        if '1' in out:
            # 迁移表
            out, err = gsql(f'ALTER TABLE gaussdb."{table}" SET SCHEMA public;')
            print(f"  结果: {out}")
            # 迁移关联的序列
            out, err = gsql(f"""
                DO $$
                DECLARE
                    seq_rec RECORD;
                BEGIN
                    FOR seq_rec IN
                        SELECT s.relname AS seq_name
                        FROM pg_class s
                        JOIN d d ON d.objid = s.oid AND d.classid = 'pg_class'::regclass AND d.refclassid = 'pg_class'::regclass
                        WHERE s.relkind = 'S' AND d.refobjid = 'gaussdb."{table}"'::regclass
                    LOOP
                        EXECUTE 'ALTER SEQUENCE gaussdb."' || seq_rec.seq_name || '" SET SCHEMA public;';
                    END LOOP;
                END $$;
            """)
            print(f"  序列: {out}")
        else:
            print(f"  已在 public 或不存在")

    # ========================================================================
    # 4. 最终验证
    # ========================================================================
    print("\n" + "=" * 60)
    print("4. 最终验证")
    print("=" * 60)
    out, err = gsql("SELECT schemaname, count(*) FROM pg_tables WHERE schemaname IN ('public', 'gaussdb') GROUP BY schemaname;")
    print(out)

    print("\npublic 表列表:")
    out, err = gsql("SELECT tablename FROM pg_tables WHERE schemaname = 'public' ORDER BY tablename;")
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
