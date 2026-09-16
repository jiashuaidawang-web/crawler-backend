#!/usr/bin/env python3
"""修复远程数据库 - 第四轮：从容器外部修复 CK，修复 PG 密码"""
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

    # ========================================================================
    # 1. 修复 ClickHouse：停止容器 → 修复配置 → 重启
    # ========================================================================
    print("=" * 60)
    print("1. 修复 ClickHouse 配置")
    print("=" * 60)

    # 停止容器
    print("停止 CK 容器...")
    out, err = run("sudo docker stop gudao-clickhouse", timeout=30)
    print(f"停止结果: {out}")
    time.sleep(2)

    # 找到 CK 配置文件的实际路径
    out, err = run("sudo docker inspect gudao-clickhouse --format '{{json .Mounts}}' 2>&1")
    print(f"CK Mounts: {out[:500]}")

    # 找到 config 的 source 路径
    out, err = run("sudo docker inspect gudao-clickhouse --format '{{range .Mounts}}{{if eq .Destination \"/etc/clickhouse-server\"}}{{.Source}}{{end}}{{end}}'")
    print(f"CK config source: {out}")

    ck_config_path = out.strip()

    if ck_config_path:
        # 删除损坏的 listen.xml
        out, err = run(f"sudo rm -f {ck_config_path}/config.d/listen.xml")
        print(f"删除损坏的 listen.xml: {out} {err}")

        # 创建正确的 listen.xml
        listen_content = '<clickhouse><listen_host>0.0.0.0</listen_host><remote_url_allow_hosts><host>100.81.0.0/16</host><host>100.97.0.0/16</host></remote_url_allow_hosts></clickhouse>'
        run(f"sudo mkdir -p {ck_config_path}/config.d")

        # 用 tee 写入
        cmd = f"echo '{listen_content}' | sudo tee {ck_config_path}/config.d/listen.xml"
        out, err = run(cmd)
        print(f"写入 listen.xml: {out}")

        # 验证
        out, err = run(f"cat {ck_config_path}/config.d/listen.xml")
        print(f"listen.xml 内容: {out}")

        # 检查 users.xml 是否被破坏
        out, err = run(f"cat {ck_config_path}/users.xml | head -30")
        print(f"users.xml 前30行:\n{out}")

        # 如果 users.xml 有问题，恢复
        if '<users>' not in out:
            print("users.xml 被破坏，尝试恢复...")
            # 检查备份
            out, err = run(f"ls -la {ck_config_path}/users.xml.bak 2>&1")
            print(f"备份: {out}")
            if 'users.xml.bak' in out:
                run(f"sudo cp {ck_config_path}/users.xml.bak {ck_config_path}/users.xml")
                print("恢复备份")

    # 启动 CK
    print("\n启动 CK 容器...")
    out, err = run("sudo docker start gudao-clickhouse", timeout=30)
    print(f"启动结果: {out}")
    time.sleep(10)

    # 检查状态
    out, err = run("sudo docker inspect gudao-clickhouse --format '{{.State.Status}} {{.State.Health.Status}}'")
    print(f"CK 状态: {out}")

    # 测试连接
    out, err = run("sudo docker exec gudao-clickhouse clickhouse-client --query 'SELECT 1'")
    print(f"CK 本地连接: [{out}] [{err}]")

    # ========================================================================
    # 2. 修复 openGauss 密码
    # ========================================================================
    print("\n" + "=" * 60)
    print("2. 修复 openGauss 密码")
    print("=" * 60)

    # 找到 PG 的 data 目录
    out, err = run("sudo docker inspect gudao-opengauss --format '{{range .Mounts}}{{if eq .Destination \"/var/lib/opengauss/data\"}}{{.Source}}{{end}}{{end}}'")
    print(f"PG data source: {out}")

    # 用 trust 方式临时允许本地连接来改密码
    # 先修改 pg_hba.conf 为 trust
    pg_data_path = out.strip()
    if pg_data_path:
        # 临时改为 trust
        cmd = f"sudo sed -i 's/host all all 0.0.0.0\\/0 sha256/host all all 0.0.0.0\\/0 trust/' {pg_data_path}/pg_hba.conf"
        out, err = run(cmd)
        print(f"修改 pg_hba.conf: {out} {err}")

        # 重启 PG
        run("sudo docker restart gudao-opengauss", timeout=30)
        time.sleep(10)

        # 现在应该可以无密码连接了
        out, err = run("sudo docker exec gudao-opengauss bash -c 'LD_LIBRARY_PATH=/usr/local/opengauss/lib:\$LD_LIBRARY_PATH /usr/local/opengauss/bin/gsql -U dbuser -d postgres -c \"SELECT 1\"'")
        print(f"PG 本地连接 (trust): [{out}] [{err}]")

        if '1' in out:
            # 设置密码
            out, err = run("""sudo docker exec gudao-opengauss bash -c 'LD_LIBRARY_PATH=/usr/local/opengauss/lib:$LD_LIBRARY_PATH /usr/local/opengauss/bin/gsql -U dbuser -d postgres -c "ALTER USER dbuser WITH PASSWORD '\''OpenGauss@2026'\'';"'""")
            print(f"设置密码: [{out}] [{err}]")

            # 改回 sha256
            cmd = f"sudo sed -i 's/host all all 0.0.0.0\\/0 trust/host all all 0.0.0.0\\/0 sha256/' {pg_data_path}/pg_hba.conf"
            run(cmd)

            # 重启 PG
            run("sudo docker restart gudao-opengauss", timeout=30)
            time.sleep(10)

    ssh.close()
    print("\n修复完成！")

if __name__ == '__main__':
    main()
