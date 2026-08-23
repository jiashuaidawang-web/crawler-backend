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
section("main_fund_flow 实际表结构")
# ============================================================
cols = q("DESCRIBE TABLE main_fund_flow").result_rows
for c in cols:
    print(f"  {c[0]:25s} {c[1]}")

# ============================================================
section("main_fund_flow stock 最新1天 TOP10 净流入")
# ============================================================
rows = q("""
    SELECT ts_code, name, main_net, super_big, big_net, mid_net, small_net
    FROM main_fund_flow
    WHERE obj_type='stock' AND trade_date=(SELECT MAX(trade_date) FROM main_fund_flow)
    ORDER BY main_net DESC LIMIT 10
""").result_rows
for r in rows:
    print(f"  {str(r[0]):12s} | {str(r[1]):10s} | net={str(r[2]):>15s} | super={str(r[3]):>14s} | big={str(r[4]):>14s} | mid={str(r[5]):>14s} | small={str(r[6]):>14s}")

# ============================================================
section("main_fund_flow stock 最新1天 TOP10 净流出")
# ============================================================
rows = q("""
    SELECT ts_code, name, main_net, super_big, big_net
    FROM main_fund_flow
    WHERE obj_type='stock' AND trade_date=(SELECT MAX(trade_date) FROM main_fund_flow)
    ORDER BY main_net ASC LIMIT 10
""").result_rows
for r in rows:
    print(f"  {str(r[0]):12s} | {str(r[1]):10s} | net={str(r[2]):>15s} | super={str(r[3]):>14s} | big={str(r[4]):>14s}")

# ============================================================
section("main_fund_flow board 名称问题 - 查 board_code 对应")
# ============================================================
print("\n[board 类型 name 字段为 None，看 board_code 能否关联 stock_board_rel]")
rows = q("""
    SELECT DISTINCT m.board_code, m.name, b.board_name
    FROM main_fund_flow m
    LEFT JOIN stock_board_rel b ON m.board_code = b.board_code
    WHERE m.obj_type='board' AND m.trade_date=(SELECT MAX(trade_date) FROM m)
    LIMIT 5
""").result_rows
for r in rows:
    print(f"  board_code={str(r[0]):10s} | fund.name={str(r[1]):10s} | rel.board_name={str(r[2])}")

# ============================================================
section("northbound_flow 数据质量 - 每天真的只有1条？")
# ============================================================
print("\n[按日期全部数据]")
rows = q("""
    SELECT trade_date, direction, time_point, net_inflow, cumulative_net_inflow, sh_net, sz_net, hk_hold_net
    FROM northbound_flow ORDER BY trade_date DESC
""").result_rows
for r in rows:
    print(f"  {r[0]} | {str(r[1]):5s} | t={str(r[2]):5s} | net={str(r[3]):>15s} | cum={str(r[4]):>15s} | sh={str(r[5]):>12s} | sz={str(r[6]):>12s} | hold={str(r[7]):>12s}")

# ============================================================
section("dragon_tiger 完整表结构")
# ============================================================
cols = q("DESCRIBE TABLE dragon_tiger").result_rows
for c in cols:
    print(f"  {c[0]:25s} {c[1]}")

# ============================================================
section("dt_detail 完整表结构")
# ============================================================
cols = q("DESCRIBE TABLE dt_detail").result_rows
for c in cols:
    print(f"  {c[0]:25s} {c[1]}")

# ============================================================
section("dt_detail 最新1天完整数据")
# ============================================================
rows = q("""
    SELECT ts_code, seat_name, seat_type, rank, buy, sell, net_buy, buy_ratio, sell_ratio
    FROM dt_detail
    WHERE trade_date=(SELECT MAX(trade_date) FROM dt_detail)
    ORDER BY ts_code, rank
""").result_rows
print(f"  共 {len(rows)} 条")
for r in rows[:30]:
    print(f"  {str(r[0]):12s} | {str(r[1]):35s} | {str(r[2]):5s} | r={str(r[3])} | buy={str(r[4]):>14s} | sell={str(r[5]):>14s} | net={str(r[6]):>14s}")

# ============================================================
section("stock_anomaly 最新1天 按 tag 分布")
# ============================================================
rows = q("""
    SELECT tag_name, tag_code, COUNT() AS cnt
    FROM stock_anomaly
    WHERE anomaly_date=(SELECT MAX(anomaly_date) FROM stock_anomaly)
    GROUP BY tag_name, tag_code ORDER BY cnt DESC
""").result_rows
for r in rows:
    print(f"  {str(r[0]):15s} ({str(r[1]):15s}) x{r[2]}")

# ============================================================
section("stock_board_rel 表结构")
# ============================================================
cols = q("DESCRIBE TABLE stock_board_rel").result_rows
for c in cols:
    print(f"  {c[0]:25s} {c[1]}")

# ============================================================
section("CROSS 验证: 今天异动 + 资金流向 能关联上吗")
# ============================================================
rows = q("""
    SELECT a.ts_code, a.tag_name, a.anomaly_id, f.main_net, f.super_big, f.big_net
    FROM stock_anomaly a
    LEFT JOIN main_fund_flow f ON a.ts_code = f.ts_code
        AND a.anomaly_date = f.trade_date AND f.obj_type='stock'
    WHERE a.anomaly_date=(SELECT MAX(anomaly_date) FROM stock_anomaly)
    ORDER BY f.main_net DESC NULLS LAST
    LIMIT 15
""").result_rows
print(f"  异动中能匹配到资金数据的:")
for r in rows:
    print(f"  {str(r[0]):12s} | {str(r[1]):10s} | anomaly_id={str(r[2]):>10s} | net={str(r[3]):>15s} | super={str(r[4]):>14s}")

print("\nDone.")
