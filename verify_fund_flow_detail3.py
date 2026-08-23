#!/usr/bin/env python3
# -*- coding: utf-8 -*-
import io, json, sys
sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding="utf-8", errors="replace")
sys.stderr = io.TextIOWrapper(sys.stderr.buffer, encoding="utf-8", errors="replace")

import clickhouse_connect
CH = clickhouse_connect.get_client(host="100.97.74.45", port=8123, username="default", password="pamirs@123", database="crawler")

def q(sql): return CH.query(sql)
def section(t):
    print(f"\n{'='*70}\n {t}\n{'='*70}")

# ============================================================
section("board 名称: 用 stock_board_rel 关联")
# ============================================================
latest = q("SELECT MAX(trade_date) FROM main_fund_flow WHERE obj_type='board'").first_row[0]
rows = q(f"""
    SELECT m.board_code, m.name, b.board_name, m.main_net
    FROM main_fund_flow m
    LEFT JOIN (
        SELECT DISTINCT board_code, board_name FROM stock_board_rel
    ) b ON m.board_code = b.board_code
    WHERE m.obj_type='board' AND m.trade_date='{latest}'
    ORDER BY m.main_net DESC LIMIT 10
""").result_rows
for r in rows:
    print(f"  code={str(r[0]):10s} | fund.name={str(r[1]):10s} | rel.name={str(r[2]):15s} | net={str(r[3]):>15s}")

# ============================================================
section("northbound_flow 数据质量评估")
# ============================================================
print("\n[所有行完整数据]")
rows = q("SELECT * FROM northbound_flow ORDER BY trade_date DESC").result_rows
col_names = q("SELECT * FROM northbound_flow LIMIT 0").column_names
for r in rows:
    print(f"  {r}")
print(f"\n  结论: 共 {len(rows)} 行，每天1行，所有值完全相同 → 疑似测试/脏数据")

# ============================================================
section("dragon_tiger 完整表结构")
# ============================================================
cols = q("DESCRIBE TABLE dragon_tiger").result_rows
for c in cols:
    print(f"  {c[0]:25s} {c[1]}")

# ============================================================
section("dragon_tiger 最新1天完整数据")
# ============================================================
latest = q("SELECT MAX(trade_date) FROM dragon_tiger").first_row[0]
rows = q(f"""
    SELECT ts_code, stock_name, reason, explanation, net_buy, total_buy, total_sell
    FROM dragon_tiger WHERE trade_date='{latest}'
    ORDER BY net_buy DESC
""").result_rows
print(f"  共 {len(rows)} 条")
for r in rows:
    print(f"  {str(r[0]):12s} | {str(r[1]):10s} | net={str(r[4]):>14s} | reason={str(r[2])[:40]}")

# ============================================================
section("dt_detail 完整表结构")
# ============================================================
cols = q("DESCRIBE TABLE dt_detail").result_rows
for c in cols:
    print(f"  {c[0]:25s} {c[1]}")

# ============================================================
section("dt_detail 最新1天 - 按股票聚合")
# ============================================================
latest = q("SELECT MAX(trade_date) FROM dt_detail").first_row[0]
rows = q(f"""
    SELECT ts_code,
           COUNT() AS seat_cnt,
           SUM(net_buy) AS total_net,
           SUM(buy) AS total_buy,
           SUM(sell) AS total_sell,
           COUNT(DISTINCT seat_type) AS type_cnt
    FROM dt_detail WHERE trade_date='{latest}'
    GROUP BY ts_code ORDER BY ABS(SUM(net_buy)) DESC LIMIT 10
""").result_rows
for r in rows:
    print(f"  {str(r[0]):12s} | 席位={r[1]} | net={str(r[2]):>14s} | buy={str(r[3]):>14s} | sell={str(r[4]):>14s}")

# ============================================================
section("dt_detail 最新1天 - 机构席位 vs 游资席位")
# ============================================================
rows = q(f"""
    SELECT seat_type,
           COUNT() AS cnt,
           SUM(net_buy) AS total_net,
           AVG(net_buy) AS avg_net
    FROM dt_detail WHERE trade_date='{latest}'
    GROUP BY seat_type
""").result_rows
for r in rows:
    print(f"  {str(r[0]):10s} | {r[1]}条 | net合计={str(r[2]):>14s} | net均值={str(r[3]):>14s}")

# ============================================================
section("stock_anomaly CROSS main_fund_flow - 验证关联")
# ============================================================
latest_anomaly = q("SELECT MAX(anomaly_date) FROM stock_anomaly").first_row[0]
latest_fund = q("SELECT MAX(trade_date) FROM main_fund_flow WHERE obj_type='stock'").first_row[0]
print(f"\n  异动最新日期: {latest_anomaly}")
print(f"  资金最新日期: {latest_fund}")

if str(latest_anomaly) == str(latest_fund):
    rows = q(f"""
        SELECT a.ts_code, a.tag_name, f.main_net, f.super_big, f.big_net
        FROM stock_anomaly a
        LEFT JOIN main_fund_flow f ON a.ts_code = f.ts_code
            AND f.obj_type='stock' AND f.trade_date='{latest_fund}'
        WHERE a.anomaly_date='{latest_anomaly}'
        ORDER BY f.main_net DESC NULLS LAST
        LIMIT 15
    """).result_rows
    print(f"\n  异动 × 资金 交叉（最新一天）:")
    for r in rows:
        print(f"  {str(r[0]):12s} | {str(r[1]):10s} | net={str(r[2]):>15s} | super={str(r[3]):>14s} | big={str(r[4]):>14s}")
else:
    print("  日期不一致，无法直接关联")

# ============================================================
section("stock_anomaly 异动 tag 完整枚举")
# ============================================================
rows = q("""
    SELECT tag_code, tag_name, COUNT() AS cnt
    FROM stock_anomaly GROUP BY tag_code, tag_name ORDER BY cnt DESC
""").result_rows
for r in rows:
    print(f"  {str(r[0]):20s} {str(r[1]):15s} x{r[2]:,}")

print("\nDone.")
