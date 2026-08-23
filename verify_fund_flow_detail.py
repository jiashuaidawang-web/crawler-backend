#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
资金流向数据结构深挖
搞清楚每张表的实际字段含义、分布、枚举值
"""
import io
import json
import sys

sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding="utf-8", errors="replace")
sys.stderr = io.TextIOWrapper(sys.stderr.buffer, encoding="utf-8", errors="replace")

import clickhouse_connect

CH = clickhouse_connect.get_client(
    host="100.97.74.45", port=8123,
    username="default", password="pamirs@123", database="crawler"
)


def q(sql, params=None):
    return CH.query(sql, params)


def section(title):
    print(f"\n{'='*70}")
    print(f" {title}")
    print(f"{'='*70}")


# ============================================================
section("1. northbound_flow 北向资金 - 实际字段 & 数据含义")
# ============================================================
print("\n[表结构]")
cols = q("DESCRIBE TABLE northbound_flow").result_rows
for c in cols:
    print(f"  {c[0]:25s} {c[1]}")

print("\n[全部数据 - 按日期+方向]")
rows = q("""
    SELECT trade_date, direction, time_point, net_inflow, buy_amount, sell_amount, cumulative_net_inflow
    FROM northbound_flow
    ORDER BY trade_date, direction
""").result_rows
for r in rows:
    print(f"  {r[0]} | dir={str(r[1]):5s} | time={str(r[10]):5s} | net_inflow={str(r[3]):>15s} | cum={str(r[6]):>15s}" if len(r)>10 else f"  {r}")

# ============================================================
section("2. main_fund_flow 主力资金 - obj_type 分布")
# ============================================================
print("\n[obj_type 分布]")
rows = q("""
    SELECT obj_type, COUNT() AS cnt, COUNT(DISTINCT trade_date) AS days
    FROM main_fund_flow GROUP BY obj_type
""").result_rows
for r in rows:
    print(f"  {str(r[0]):10s} | 总行数={r[1]:>6,} | 天数={r[2]}")

print("\n[board 类型最新1天 TOP10 净流入]")
rows = q("""
    SELECT board_code, name, main_net, super_big, big_net, mid_net, small_net
    FROM main_fund_flow
    WHERE obj_type='board' AND trade_date=(SELECT MAX(trade_date) FROM main_fund_flow)
    ORDER BY main_net DESC LIMIT 10
""").result_rows
for r in rows:
    print(f"  {str(r[0]):10s} | {str(r[1]):15s} | net={str(r[2]):>15s} | super={str(r[3]):>14s} | big={str(r[4]):>14s}")

print("\n[stock 类型最新1天 TOP10 净流入]")
rows = q("""
    SELECT code, name, main_net, super_big, big_net, change_rate
    FROM main_fund_flow
    WHERE obj_type='stock' AND trade_date=(SELECT MAX(trade_date) FROM main_fund_flow)
    ORDER BY main_net DESC LIMIT 10
""").result_rows
for r in rows:
    print(f"  {str(r[0]):10s} | {str(r[1]):10s} | net={str(r[2]):>15s} | super={str(r[3]):>14s} | chg={str(r[5])}")

# ============================================================
section("3. dragon_tiger 龙虎榜 - 字段")
# ============================================================
print("\n[表结构]")
cols = q("DESCRIBE TABLE dragon_tiger").result_rows
for c in cols:
    print(f"  {c[0]:25s} {c[1]}")

print("\n[最新1天上榜原因分布]")
rows = q("""
    SELECT explanation, COUNT() AS cnt
    FROM dragon_tiger
    WHERE trade_date=(SELECT MAX(trade_date) FROM dragon_tiger)
    GROUP BY explanation ORDER BY cnt DESC
""").result_rows
for r in rows:
    print(f"  {str(r[0]):50s} x{r[1]}")

print("\n[最新1天净买额 TOP10]")
rows = q("""
    SELECT ts_code, stock_name, reason, net_buy, total_buy, total_sell
    FROM dragon_tiger
    WHERE trade_date=(SELECT MAX(trade_date) FROM dragon_tiger)
    ORDER BY net_buy DESC LIMIT 10
""").result_rows
for r in rows:
    print(f"  {str(r[0]):12s} | {str(r[1]):10s} | net={str(r[3]):>14s} | reason={str(r[2])[:30]}")

# ============================================================
section("4. dt_detail 龙虎榜席位 - 字段")
# ============================================================
print("\n[表结构]")
cols = q("DESCRIBE TABLE dt_detail").result_rows
for c in cols:
    print(f"  {c[0]:25s} {c[1]}")

print("\n[seat_type 分布]")
rows = q("SELECT seat_type, COUNT() AS cnt FROM dt_detail GROUP BY seat_type").result_rows
for r in rows:
    print(f"  {str(r[0]):15s} x{r[1]}")

print("\n[最新1天席位明细]")
rows = q("""
    SELECT ts_code, seat_name, seat_type, rank, buy, sell, net_buy
    FROM dt_detail
    WHERE trade_date=(SELECT MAX(trade_date) FROM dt_detail)
    ORDER BY ts_code, rank
    LIMIT 20
""").result_rows
for r in rows:
    print(f"  {str(r[0]):12s} | {str(r[1]):30s} | {str(r[2]):5s} | rank={str(r[3])} | net={str(r[6]):>14s}")

# ============================================================
section("5. stock_anomaly 异动 - 与资金交叉的关联字段")
# ============================================================
print("\n[tag_name 分布(最新1天)]")
rows = q("""
    SELECT tag_name, COUNT() AS cnt
    FROM stock_anomaly
    WHERE anomaly_date=(SELECT MAX(anomaly_date) FROM stock_anomaly)
    GROUP BY tag_name ORDER BY cnt DESC
""").result_rows
for r in rows:
    print(f"  {str(r[0]):15s} x{r[1]}")

print("\n[最新1天异动样本]")
rows = q("""
    SELECT ts_code, tag_name, reason
    FROM stock_anomaly
    WHERE anomaly_date=(SELECT MAX(anomaly_date) FROM stock_anomaly)
    LIMIT 5
""").result_rows
for r in rows:
    print(f"  {str(r[0]):12s} | {str(r[1]):10s} | {str(r[2])[:60]}")

# ============================================================
section("6. stock_board_rel 板块关联 - 用于资金→板块→个股下钻")
# ============================================================
print("\n[board_type 分布]")
rows = q("SELECT board_type, COUNT(DISTINCT board_code) AS boards, COUNT() AS cnt FROM stock_board_rel GROUP BY board_type").result_rows
for r in rows:
    type_name = {"1": "地域", "2": "行业", "3": "概念"}.get(str(r[0]), str(r[0]))
    print(f"  type={r[0]}({type_name}) | 板块数={r[1]:>4,} | 关联数={r[2]:>6,}")

print("\n[最新1天新增/变化]")
rows = q("""
    SELECT board_code, board_name, COUNT() AS cnt
    FROM stock_board_rel
    WHERE effective_date=(SELECT MAX(effective_date) FROM stock_board_rel)
    GROUP BY board_code, board_name ORDER BY cnt DESC LIMIT 10
""").result_rows
for r in rows:
    print(f"  {str(r[0]):10s} | {str(r[1]):15s} | {r[2]}只")

print("\nDone.")
