#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
================================================================================
数据同步脚本：本地 Windows → 远程云服务器 (100.81.7.96)
================================================================================
用途：每天任务执行后，将本地 ClickHouse + openGauss 的增量数据同步到远程备份
依赖：pip install -r requirements.txt
用法：python sync_to_remote.py [--full] [--status]

ClickHouse: 本地 127.0.0.1:8123 → 远程 100.81.7.96:8123 (TailScale 内网直连)
openGauss:  本地 127.0.0.1:5432 → 远程 100.81.7.96:5432 (TailScale 内网直连)
================================================================================
"""

import os
import sys
import json
import logging
import argparse
import time
from datetime import datetime, date
from pathlib import Path

# ---------------------------------------------------------------------------
# 检查依赖
# ---------------------------------------------------------------------------
try:
    import clickhouse_connect
except ImportError:
    print("[ERROR] 缺少 clickhouse-connect，请执行: pip install clickhouse-connect")
    sys.exit(1)

try:
    import psycopg2
    import psycopg2.extras
except ImportError:
    print("[ERROR] 缺少 psycopg2，请执行: pip install psycopg2-binary")
    sys.exit(1)

# ---------------------------------------------------------------------------
# 配置
# ---------------------------------------------------------------------------
SCRIPT_DIR = Path(__file__).parent.resolve()
SYNC_STATUS_FILE = SCRIPT_DIR / "sync_status.json"
LOG_FILE = SCRIPT_DIR / "sync.log"

# --- 本地连接 ---
CK_LOCAL = dict(host="127.0.0.1", port=8123, username="default",
                   password="pamirs@123", database="crawler")
PG_LOCAL = dict(host="127.0.0.1", port=5432, user="dbuser",
                   password="OpenGauss@2026", dbname="postgres")

# --- 远程连接 ---
CK_REMOTE = dict(host="100.81.7.96", port=8123, username="default",
                    password="pamirs@123", database="crawler")
PG_REMOTE = dict(host="100.81.7.96", port=5432, user="dbuser",
                    password="OpenGauss@2026", dbname="postgres")

# --- ClickHouse 表：表名 → 增量字段 (按 trade_date，没有则用 create_date) ---
CK_TABLES = {
    # 行情核心
    "stock_daily":          "trade_date",
    "stock_weekly":         "trade_date",
    "stock_kline_minute":   "trade_date",
    "index_daily":          "trade_date",
    "board_daily":          "trade_date",
    "board_basic":          "create_date",
    # 池表
    "limit_up_pool":        "trade_date",
    "limit_down_pool":      "trade_date",
    "zhaban_pool":          "trade_date",
    "strong_pool":          "trade_date",
    "cixin_pool":           "trade_date",
    # 主题
    "dragon_tiger":         "trade_date",
    "dt_detail":            "trade_date",
    "main_fund_flow":       "trade_date",
    "stock_board_rel":      "effective_date",
    "northbound_flow":      "trade_date",
    # 辅助
    "concept":              "create_date",
    "financial":            "end_date",
    "news_event":           "event_time",
    "sentiment_daily":      "trade_date",
    "theme_factor_daily":   "trade_date",
    "trend_candidate_daily": "trade_date",
    "four_dimension_daily": "trade_date",
    "trade_calendar":       "trade_date",
    "mainline_daily":       "trade_date",
    "leader_pool_daily":    "trade_date",
    "stock_task_config":    "create_date",
}

# --- openGauss 表：操作型表 + 业务表 ---
PG_TABLES = {
    # 调度系统
    "crawl_task":           "created_at",
    "crawl_log":            "created_at",
    "crawl_alert":          "created_at",
    "crawl_node":           "created_at",
    "crawl_stock_backfill_status": "updated_at",
    "crawl_ip_consumption": "created_at",
    # 任务编排
    "job_definition":       "created_at",
    "job_execution":        "created_at",
    "pipeline_run":         "created_at",
    "pipeline_stage":       "created_at",
    "worker_node":          "created_at",
    # 业务操作
    "trade_log":            "trade_date",
}

# --- 表所在的 schema（默认 public，其他在此指定）---
PG_TABLE_SCHEMAS = {
    # gaussdb schema 中的表
    "crawl_alert":          "gaussdb",
    "crawl_log":            "gaussdb",
    "crawl_node":           "gaussdb",
    "crawl_stock_backfill_status": "gaussdb",
    "crawl_task":           "gaussdb",
    "stock_board_rel":      "gaussdb",
    "trade_log":            "gaussdb",
}

# --- ReplacingMergeTree 表 (CK 自动去重，不需要额外处理) ---
CK_REPLACING_TABLES = {
    "board_basic", "stock_board_rel", "concept",
    "sentiment_daily", "trade_calendar", "mainline_daily", "leader_pool_daily",
}

# --- 批量大小 ---
BATCH_SIZE = 5000

# ---------------------------------------------------------------------------
# 日志
# ---------------------------------------------------------------------------
def setup_logging():
    fmt = logging.Formatter("%(asctime)s [%(levelname)s] %(message)s")
    handlers = [
        logging.StreamHandler(sys.stdout),
        logging.FileHandler(LOG_FILE, encoding="utf-8"),
    ]
    root = logging.getLogger()
    root.setLevel(logging.INFO)
    for h in handlers:
        h.setFormatter(fmt)
        root.addHandler(h)
    return logging.getLogger("sync")

logger = setup_logging()

# ---------------------------------------------------------------------------
# 同步状态
# ---------------------------------------------------------------------------
def load_status() -> dict:
    if SYNC_STATUS_FILE.exists():
        try:
            with open(SYNC_STATUS_FILE, "r", encoding="utf-8") as f:
                return json.load(f)
        except Exception as e:
            logger.warning(f"加载状态文件失败: {e}")
    return {"ck": {}, "pg": {}, "last_full_sync": None}

def save_status(status: dict):
    with open(SYNC_STATUS_FILE, "w", encoding="utf-8") as f:
        json.dump(status, f, indent=2, ensure_ascii=False, default=str)

# ---------------------------------------------------------------------------
# ClickHouse 同步器
# ---------------------------------------------------------------------------
class ClickHouseSyncer:
    def __init__(self, local_cfg, remote_cfg):
        logger.info("连接本地 ClickHouse ...")
        self.local = clickhouse_connect.get_client(**local_cfg)
        logger.info("连接远程 ClickHouse (100.81.7.96) ...")
        self.remote = clickhouse_connect.get_client(**remote_cfg)
        logger.info("ClickHouse 连接成功")

    def close(self):
        try: self.local.close()
        except: pass
        try: self.remote.close()
        except: pass

    def ensure_table_exists(self, table: str):
        """确保远程表存在（不存在则从本地 DDL 创建）"""
        try:
            self.remote.command(f"SELECT 1 FROM {table} LIMIT 0")
        except Exception:
            logger.info(f"  远程表 {table} 不存在，正在创建 ...")
            # 获取本地建表语句
            ddl = self.local.command(f"SHOW CREATE TABLE {table}")
            # 在远程执行
            self.remote.command(ddl)
            logger.info(f"  远程表 {table} 创建完成")

    def get_max_date(self, table: str, date_col: str):
        try:
            result = self.local.query(f"SELECT MAX(`{date_col}`) FROM `{table}`")
            val = result.result_rows[0][0]
            return str(val) if val else None
        except Exception as e:
            logger.warning(f"  获取 {table} 最大 {date_col} 失败: {e}")
            return None

    def sync_table(self, table: str, date_col: str, last_sync) -> int:
        """同步单张 CK 表，返回行数"""
        self.ensure_table_exists(table)

        # 构建查询
        if last_sync:
            query = f"SELECT * FROM `{table}` WHERE `{date_col}` > %(last)s"
            params = {"last": last_sync}
        else:
            query = f"SELECT * FROM `{table}`"
            params = None

        result = self.local.query(query, params)
        rows = result.result_rows
        if not rows:
            logger.info(f"  {table}: 无新增数据")
            return 0

        columns = result.column_names

        # 批量写入
        total = 0
        for i in range(0, len(rows), BATCH_SIZE):
            batch = rows[i:i + BATCH_SIZE]
            self.remote.insert(table, batch, column_names=columns)
            total += len(batch)

        logger.info(f"  {table}: 同步 {total} 行")
        return total

# ---------------------------------------------------------------------------
# openGauss 同步器
# ---------------------------------------------------------------------------
class OpenGaussSyncer:
    def __init__(self, local_cfg, remote_cfg):
        logger.info("连接本地 openGauss ...")
        self.local_conn = psycopg2.connect(**local_cfg)
        self.local_conn.autocommit = False
        logger.info("连接远程 openGauss (100.81.7.96) ...")
        self.remote_conn = psycopg2.connect(**remote_cfg)
        self.remote_conn.autocommit = False
        logger.info("openGauss 连接成功")

    def close(self):
        try: self.local_conn.close()
        except: pass
        try: self.remote_conn.close()
        except: pass

    def _table_qname(self, table: str) -> str:
        """获取带 schema 的表名"""
        schema = PG_TABLE_SCHEMAS.get(table, "public")
        return f'"{schema}"."{table}"'

    def ensure_table_exists(self, table: str):
        """确保远程表存在"""
        qname = self._table_qname(table)
        try:
            with self.remote_conn.cursor() as cur:
                cur.execute(f'SELECT 1 FROM {qname} LIMIT 0')
            self.remote_conn.rollback()
        except Exception:
            logger.info(f"  远程表 {qname} 不存在，需要先在远程执行 schema-full-rebuild.sql")
            raise Exception(f"远程 openGauss 表 {qname} 不存在，请先初始化")

    def get_columns(self, table: str) -> list:
        schema = PG_TABLE_SCHEMAS.get(table, "public")
        with self.local_conn.cursor() as cur:
            cur.execute("""
                SELECT column_name FROM information_schema.columns
                WHERE table_schema = %s AND table_name = %s
                ORDER BY ordinal_position
            """, (schema, table))
            return [r[0] for r in cur.fetchall()]

    def get_primary_key(self, table: str) -> list:
        schema = PG_TABLE_SCHEMAS.get(table, "public")
        with self.local_conn.cursor() as cur:
            cur.execute("""
                SELECT a.attname
                FROM pg_index i
                JOIN pg_attribute a ON a.attrelid = i.indrelid AND a.attnum = ANY(i.indkey)
                WHERE i.indrelid = %s::regclass AND i.indisprimary
            """, (f'{schema}.{table}',))
            return [r[0] for r in cur.fetchall()]

    def sync_table(self, table: str, date_col: str, last_sync) -> int:
        """同步单张 PG 表，返回行数"""
        self.ensure_table_exists(table)
        qname = self._table_qname(table)

        columns = self.get_columns(table)
        if not columns:
            logger.warning(f"  {table}: 表不存在")
            return 0

        pk_columns = self.get_primary_key(table)

        # 构建查询
        col_list = ", ".join(f'"{c}"' for c in columns)
        query = f'SELECT {col_list} FROM {qname}'
        params = []
        if last_sync:
            query += f' WHERE "{date_col}" > %s'
            params.append(last_sync)

        with self.local_conn.cursor() as cur:
            cur.execute(query, params)
            rows = cur.fetchall()

        if not rows:
            logger.info(f"  {table}: 无新增数据")
            return 0

        # INSERT ... ON CONFLICT
        placeholders = ", ".join(["%s"] * len(columns))
        insert_sql = f'INSERT INTO {qname} ({col_list}) VALUES ({placeholders})'

        if pk_columns:
            pk_list = ", ".join(f'"{c}"' for c in pk_columns)
            non_pk = [c for c in columns if c not in pk_columns]
            if non_pk:
                update_set = ", ".join(f'"{c}" = EXCLUDED."{c}"' for c in non_pk)
                insert_sql += f' ON CONFLICT ({pk_list}) DO UPDATE SET {update_set}'
            else:
                insert_sql += f' ON CONFLICT ({pk_list}) DO NOTHING'

        with self.remote_conn.cursor() as cur:
            psycopg2.extras.execute_batch(cur, insert_sql, rows, page_size=1000)
            self.remote_conn.commit()

        logger.info(f"  {table}: 同步 {len(rows)} 行")
        return len(rows)

# ---------------------------------------------------------------------------
# 主逻辑
# ---------------------------------------------------------------------------
def run_sync(full: bool = False):
    status = load_status()
    if full:
        logger.info("=== 全量同步模式 ===")
        ck_status = {}
        pg_status = {}
    else:
        ck_status = status.get("ck", {})
        pg_status = status.get("pg", {})

    result = {
        "start_time": datetime.now().isoformat(),
        "ck_total": 0, "pg_total": 0,
        "ck_tables": {}, "pg_tables": {},
        "errors": [],
    }

    # --- ClickHouse ---
    logger.info("=" * 60)
    logger.info("ClickHouse 同步开始")
    logger.info("=" * 60)
    ck_syncer = None
    try:
        ck_syncer = ClickHouseSyncer(CK_LOCAL, CK_REMOTE)
        for table, date_col in CK_TABLES.items():
            try:
                last = ck_status.get(table)
                rows = ck_syncer.sync_table(table, date_col, last)
                result["ck_total"] += rows
                result["ck_tables"][table] = rows

                if rows > 0 or not last:
                    new_last = ck_syncer.get_max_date(table, date_col)
                    if new_last:
                        ck_status[table] = new_last
            except Exception as e:
                msg = f"CK:{table}: {e}"
                logger.error(f"  {msg}")
                result["errors"].append(msg)
    except Exception as e:
        msg = f"CK 连接失败: {e}"
        logger.error(msg)
        result["errors"].append(msg)
    finally:
        if ck_syncer:
            ck_syncer.close()

    # --- openGauss ---
    logger.info("=" * 60)
    logger.info("openGauss 同步开始")
    logger.info("=" * 60)
    pg_syncer = None
    try:
        pg_syncer = OpenGaussSyncer(PG_LOCAL, PG_REMOTE)
        for table, date_col in PG_TABLES.items():
            try:
                last = pg_status.get(table)
                rows = pg_syncer.sync_table(table, date_col, last)
                result["pg_total"] += rows
                result["pg_tables"][table] = rows

                if rows > 0 or not last:
                    with pg_syncer.local_conn.cursor() as cur:
                        cur.execute(f'SELECT MAX("{date_col}") FROM "{table}"')
                        new_last = cur.fetchone()[0]
                        if new_last:
                            pg_status[table] = str(new_last)
            except Exception as e:
                msg = f"PG:{table}: {e}"
                logger.error(f"  {msg}")
                result["errors"].append(msg)
                try: pg_syncer.remote_conn.rollback()
                except: pass
    except Exception as e:
        msg = f"PG 连接失败: {e}"
        logger.error(msg)
        result["errors"].append(msg)
    finally:
        if pg_syncer:
            pg_syncer.close()

    # --- 保存状态 ---
    status["ck"] = ck_status
    status["pg"] = pg_status
    status["last_sync"] = datetime.now().isoformat()
    if full:
        status["last_full_sync"] = datetime.now().isoformat()
    save_status(status)

    # --- 结果 ---
    result["end_time"] = datetime.now().isoformat()
    result["success"] = len(result["errors"]) == 0

    logger.info("=" * 60)
    logger.info(f"同步完成: CK {result['ck_total']} 行, PG {result['pg_total']} 行")
    if result["errors"]:
        logger.warning(f"错误 {len(result['errors'])} 项:")
        for e in result["errors"]:
            logger.warning(f"  - {e}")
    logger.info("=" * 60)

    return result


def show_status():
    status = load_status()
    print("\n--- 同步状态 ---")
    print(f"上次同步: {status.get('last_sync', '从未')}")
    print(f"上次全量: {status.get('last_full_sync', '从未')}")
    print(f"\nClickHouse ({len(status.get('ck', {}))} 表):")
    for t, d in status.get("ck", {}).items():
        print(f"  {t}: {d}")
    print(f"\nopenGauss ({len(status.get('pg', {}))} 表):")
    for t, d in status.get("pg", {}).items():
        print(f"  {t}: {d}")


# ---------------------------------------------------------------------------
# 入口
# ---------------------------------------------------------------------------
def main():
    parser = argparse.ArgumentParser(description="数据同步: 本地 → 远程 (100.81.7.96)")
    parser.add_argument("--full", action="store_true", help="全量同步（忽略上次状态）")
    parser.add_argument("--status", action="store_true", help="查看同步状态")
    args = parser.parse_args()

    if args.status:
        show_status()
        return

    result = run_sync(full=args.full)
    sys.exit(0 if result["success"] else 1)


if __name__ == "__main__":
    main()
