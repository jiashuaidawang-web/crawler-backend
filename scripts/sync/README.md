# 数据同步：本地 → 远程云服务器

## 概述

每天任务执行完后，将本地 ClickHouse + openGauss 的增量数据同步到远程备份服务器 (100.81.7.96)。

```
┌─────────────────────────────┐         TailScale 内网          ┌─────────────────────────────┐
│       本地 Windows          │  ────────────────────────────▶  │    远程 Debian 13 (KVM)     │
│                             │                                 │                             │
│  Docker Desktop (不稳定)    │     CK: 100.81.7.96:8123        │  Docker (host 模式, 稳定)   │
│  ├── ClickHouse :8123       │     PG: 100.81.7.96:5432        │  ├── ClickHouse :8123       │
│  └── openGauss  :5432       │                                 │  └── openGauss  :5432       │
│                             │                                 │                             │
│  crawler-admin :48080       │                                 │  (未来可部署应用)           │
│  IDEA 开发环境              │                                 │                             │
└─────────────────────────────┘                                 └─────────────────────────────┘
```

## 文件清单

| 文件 | 说明 |
|------|------|
| `sync_to_remote.py` | 核心同步脚本（Python） |
| `remote_init.sh` | 远程服务器初始化脚本 |
| `requirements.txt` | Python 依赖 |
| `sync_status.json` | 同步状态记录（自动生成） |
| `sync.log` | 同步日志（自动生成） |

## 快速开始

### 1. 安装 Python 依赖（本地）

```bash
pip install -r scripts/sync/requirements.txt
```

### 2. 远程服务器初始化（一次性）

在远程服务器 (100.81.7.96) 上执行：

```bash
# 方式一：直接执行 SQL
clickhouse-client --host=127.0.0.1 --port=8123 --user=default --password=pamirs@123 \
    --database=crawler < clickhouse-schema.sql

psql -h 127.0.0.1 -p 5432 -U dbuser -d postgres -f schema-full-rebuild.sql

# 方式二：使用初始化脚本
bash scripts/sync/remote_init.sh
```

### 3. 配置远程访问

**ClickHouse** — 允许 TailScale 网段连接：

```xml
<!-- /etc/clickhouse-server/config.d/remote_access.xml -->
<clickhouse>
    <listen_host>0.0.0.0</listen_host>
    <remote_url_allow_hosts>
        <host>100.81.0.0/16</host>
    </remote_url_allow_hosts>
</clickhouse>
```

```bash
docker restart clickhouse-server
```

**openGauss** — 配置 pg_hba.conf：

```bash
echo "host    all             all             100.81.0.0/16           md5" \
    >> /var/lib/opengauss/data/pg_hba.conf
docker restart opengauss
```

### 4. 执行同步

```bash
# 增量同步（默认）
python scripts/sync/sync_to_remote.py

# 全量同步
python scripts/sync/sync_to_remote.py --full

# 查看状态
python scripts/sync/sync_to_remote.py --status
```

### 5. 通过 API 触发（前端按钮）

```bash
# 增量同步
curl -X POST http://localhost:48080/api/sync/trigger

# 全量同步
curl -X POST http://localhost:48080/api/sync/trigger?full=true

# 查询状态
curl http://localhost:48080/api/sync/status
```

## 同步策略

### ClickHouse（26 张表）

| 类别 | 表名 | 增量字段 | 引擎 |
|------|------|----------|------|
| 行情 | stock_daily, stock_weekly, index_daily, board_daily | trade_date | MergeTree |
| 池子 | limit_up/down, zhaban, strong, cixin | trade_date | MergeTree |
| 主题 | dragon_tiger, dt_detail, main_fund_flow, northbound_flow | trade_date | MergeTree |
| 维表 | board_basic, concept, stock_board_rel | create_date | ReplacingMergeTree |
| 计算层 | sentiment_daily, theme_factor, trend_candidate, four_dimension, trade_calendar, mainline, leader_pool | trade_date | MergeTree/Replacing |
| 其他 | stock_kline_minute, financial, news_event, stock_task_config | 各自字段 | MergeTree |

### openGauss（7 张表）

| 表名 | 增量字段 | 说明 |
|------|----------|------|
| crawl_task, crawl_log, crawl_alert, crawl_node | created_at | 调度系统 |
| crawl_stock_backfill_status | updated_at | 回填进度 |
| trade_log, trade_calendar | trade_date | 业务数据 |

## 配置项（application.yml）

```yaml
sync:
  enabled: true
  script:
    path: scripts/sync/sync_to_remote.py
  python:
    cmd: python   # Windows 用 python，Linux 用 python3
```

## 常见问题

**Q: 同步失败怎么办？**
A: 查看 `scripts/sync/sync.log` 获取详细错误信息。常见原因：
- 远程服务未启动
- 防火墙/网络不通
- 远程表未创建

**Q: 数据量大会不会很慢？**
A: 首次全量同步可能较慢（取决于数据量），后续增量同步只传输变化数据。

**Q: 同步过程中本地任务在写数据会怎样？**
A: 增量同步基于时间戳判断，不会锁表。建议任务执行完后再同步。

**Q: 如何验证同步成功？**
A: 远程查询比对：
```sql
-- ClickHouse
SELECT count() FROM stock_daily WHERE trade_date = today()

-- openGauss
SELECT count(*) FROM crawl_task
```
