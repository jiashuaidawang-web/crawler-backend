#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
资金流向数据健康检查脚本
验证 ClickHouse 中已有的资金流向相关表的数据现状
"""
import io
import json
import sys
from datetime import datetime, timedelta

# Windows 控制台 UTF-8
sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding="utf-8", errors="replace")
sys.stderr = io.TextIOWrapper(sys.stderr.buffer, encoding="utf-8", errors="replace")

try:
    import clickhouse_connect
except ImportError:
    print("需要安装: pip install clickhouse-connect")
    sys.exit(1)

# ClickHouse 连接配置
CH_CONFIG = {
    "host": "100.97.74.45",
    "port": 8123,
    "username": "default",
    "password": "pamirs@123",
    "database": "crawler",
}

# 要检查的表和关键字段
TABLES = {
    "northbound_flow": {
        "name": "北向资金",
        "date_col": "trade_date",
        "key_cols": ["board_type", "board_name", "net_buy_amt", "day_net_amt_in", "index_chg"],
    },
    "main_fund_flow": {
        "name": "主力资金流向(个股+板块)",
        "date_col": "trade_date",
        "key_cols": ["obj_type", "code", "name", "main_net", "super_big_net", "big_net", "mid_net", "small_net"],
    },
    "dragon_tiger": {
        "name": "龙虎榜",
        "date_col": "trade_date",
        "key_cols": ["security_code", "security_name", "explanation", "billboard_net", "billboard_buy", "billboard_sell"],
    },
    "dt_detail": {
        "name": "龙虎榜详情(席位)",
        "date_col": "trade_date",
        "key_cols": ["security_code", "operate_dept_name", "trade_direction", "buy_amt", "sell_amt", "net_buy"],
    },
    "stock_anomaly": {
        "name": "个股异动",
        "date_col": "anomaly_date",
        "key_cols": ["ts_code", "anomaly_id", "tag_code", "tag_name", "reason"],
    },
    "stock_board_rel": {
        "name": "股票-板块关联",
        "date_col": "effective_date",
        "key_cols": ["ts_code", "board_code", "board_name", "board_type"],
    },
}


def connect():
    client = clickhouse_connect.get_client(
        host=CH_CONFIG["host"],
        port=CH_CONFIG["port"],
        username=CH_CONFIG["username"],
        password=CH_CONFIG["password"],
        database=CH_CONFIG["database"],
    )
    return client


def check_table(client, table_name, info):
    """检查单张表的数据健康度"""
    result = {"table": table_name, "name": info["name"], "status": "unknown"}

    try:
        # 1. 总行数
        row = client.query(f"SELECT COUNT() AS cnt FROM {table_name}").first_row
        result["total_rows"] = row[0]

        if row[0] == 0:
            result["status"] = "empty"
            return result

        # 2. 日期范围
        date_col = info["date_col"]
        row = client.query(
            f"SELECT MIN({date_col}) AS min_d, MAX({date_col}) AS max_d FROM {table_name}"
        ).first_row
        result["min_date"] = str(row[0])
        result["max_date"] = str(row[1])

        # 3. 最近 30 天有多少天有数据
        row = client.query(
            f"SELECT COUNT(DISTINCT {date_col}) AS days FROM {table_name} "
            f"WHERE {date_col} >= today() - 30"
        ).first_row
        result["recent_30d_days"] = row[0]

        # 4. 最新一天的行数
        row = client.query(
            f"SELECT COUNT() AS cnt FROM {table_name} WHERE {date_col} = (SELECT MAX({date_col}) FROM {table_name})"
        ).first_row
        result["latest_date_rows"] = row[0]

        # 5. 关键字段填充率
        fill_rates = {}
        for col in info["key_cols"]:
            try:
                r = client.query(
                    f"SELECT COUNTIf({col} IS NOT NULL AND {col} != '' AND {col} != 0) AS filled, "
                    f"COUNT() AS total FROM {table_name}"
                ).first_row
                fill_rates[col] = f"{r[0]/r[1]*100:.1f}%" if r[1] > 0 else "N/A"
            except Exception:
                fill_rates[col] = "ERR"
        result["fill_rates"] = fill_rates

        # 6. 最新一天样本（前 3 条）
        try:
            rows = client.query(
                f"SELECT * FROM {table_name} WHERE {date_col} = (SELECT MAX({date_col}) FROM {table_name}) "
                f"LIMIT 3",
                query_formats={"FixedString": "string"},
            ).result_rows
            col_names = client.query(
                f"SELECT * FROM {table_name} LIMIT 0"
            ).column_names
            samples = []
            for r in rows[:2]:  # 只取 2 条展示
                samples.append({col_names[i]: str(v)[:80] for i, v in enumerate(r)})
            result["samples"] = samples
        except Exception as e:
            result["samples"] = [f"Error: {e}"]

        result["status"] = "ok"

    except Exception as e:
        result["status"] = "error"
        result["error"] = str(e)

    return result


def main():
    print("=" * 80)
    print("资金流向数据健康检查")
    print(f"时间: {datetime.now().strftime('%Y-%m-%d %H:%M:%S')}")
    print(f"目标: {CH_CONFIG['host']}:{CH_CONFIG['port']}/{CH_CONFIG['database']}")
    print("=" * 80)

    try:
        client = connect()
        # 测试连接
        client.query("SELECT 1")
        print("✅ 连接成功\n")
    except Exception as e:
        print(f"❌ 连接失败: {e}")
        sys.exit(1)

    all_results = []
    for table, info in TABLES.items():
        print(f"\n{'─' * 60}")
        print(f"📊 {table} ({info['name']})")
        print(f"{'─' * 60}")

        r = check_table(client, table, info)
        all_results.append(r)

        if r["status"] == "empty":
            print("  ⚠️  表为空，无数据")
        elif r["status"] == "error":
            print(f"  ❌ 查询错误: {r.get('error', 'unknown')}")
        elif r["status"] == "ok":
            print(f"  总行数:         {r['total_rows']:,}")
            print(f"  日期范围:       {r['min_date']} ~ {r['max_date']}")
            print(f"  近30天有数据:   {r['recent_30d_days']} 天")
            print(f"  最新一天行数:   {r['latest_date_rows']:,}")
            print(f"  字段填充率:")
            for col, rate in r["fill_rates"].items():
                print(f"    {col:20s} {rate}")
            if r.get("samples"):
                print(f"  样本(最新1天):")
                for i, s in enumerate(r["samples"]):
                    print(f"    [{i+1}] {json.dumps(s, ensure_ascii=False)[:200]}")

    # 汇总
    print(f"\n{'=' * 80}")
    print("汇总")
    print(f"{'=' * 80}")
    print(f"{'表名':<25s} {'名称':<20s} {'总行数':>10s} {'最新日期':<12s} {'状态':<10s}")
    print("-" * 80)
    for r in all_results:
        status_icon = {"ok": "✅", "empty": "⚠️", "error": "❌"}.get(r["status"], "?")
        total = r.get("total_rows", "N/A")
        total_str = f"{total:,}" if isinstance(total, int) else str(total)
        latest = r.get("max_date", "N/A") or "N/A"
        print(f"{r['table']:<25s} {r['name']:<20s} {total_str:>10s} {latest:<12s} {status_icon} {r['status']}")

    # 输出 JSON 供后续使用
    json_path = "verify_fund_flow_result.json"
    with open(json_path, "w", encoding="utf-8") as f:
        json.dump(all_results, f, ensure_ascii=False, indent=2, default=str)
    print(f"\n详细结果已保存: {json_path}")


if __name__ == "__main__":
    main()
