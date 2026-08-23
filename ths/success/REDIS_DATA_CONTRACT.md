# 同花顺 L2 Redis 数据契约

> 版本：v1.0
> 日期：2026-08-23
> 设计目标：秒级延迟、支持 50-500 支股票、交易时段持续写入

---

## 1. 数据模型总览

```
┌─────────────────────────────────────────────────────────────┐
│                    Redis Key 空间                           │
├─────────────────────────────────────────────────────────────┤
│                                                             │
│  ths:l2:pool                    ← Set (股票池)              │
│  ths:l2:quote:{code}            ← Hash (盘口快照)           │
│  ths:l2:tick:{code}             ← Stream (逐笔成交流)       │
│  ths:l2:status:{code}           ← Hash (实时状态)           │
│  ths:l2:config                  ← Hash (系统配置)           │
│                                                             │
└─────────────────────────────────────────────────────────────┘
```

---

## 2. 股票池 (Set)

```
Key: ths:l2:pool
Type: SET
Value: 股票代码集合
```

```bash
# 添加股票到订阅池
SADD ths:l2:pool 600519 000001 600667 300750

# 查看订阅池
SMEMBERS ths:l2:pool

# 移除股票
SREM ths:l2:pool 600667

# 查看订阅数量
SCARD ths:l2:pool
```

---

## 3. 盘口快照 (Hash)

```
Key: ths:l2:quote:{code}
   例如: ths:l2:quote:600519
Type: HASH
TTL: 60 秒（无更新则过期）
```

### Field 定义

| Field | 类型 | 说明 | 示例 |
|-------|------|------|------|
| code | String | 股票代码 | "600519" |
| last_price | Float | 最新价 | 1850.50 |
| open | Float | 开盘价 | 1840.00 |
| high | Float | 最高价 | 1860.00 |
| low | Float | 最低价 | 1835.00 |
| prev_close | Float | 昨收 | 1830.00 |
| volume | Long | 总成交量(手) | 123456 |
| amount | Double | 总成交额 | 2280000000.00 |
| bid1_p | Float | 买一价 | 1850.00 |
| bid1_v | Long | 买一量 | 500 |
| bid2_p | Float | 买二价 | 1849.90 |
| bid2_v | Long | 买二量 | 300 |
| ... | ... | ... | ... |
| bid10_p | Float | 买十价 | 1845.00 |
| bid10_v | Long | 买十量 | 100 |
| ask1_p | Float | 卖一价 | 1850.50 |
| ask1_v | Long | 卖一量 | 200 |
| ask2_p | Float | 卖二价 | 1850.60 |
| ask2_v | Long | 卖二量 | 400 |
| ... | ... | ... | ... |
| ask10_p | Float | 卖十价 | 1855.00 |
| ask10_v | Long | 卖十量 | 150 |
| timestamp | Long | 时间戳(ms) | 1724396100000 |
| update_time | String | 更新时间 | "2026-08-23 10:30:00" |

### Redis 命令

```bash
# 写入盘口快照（Pipeline 批量）
HSET ths:l2:quote:600519 \
  last_price 1850.50 \
  bid1_p 1850.00 bid1_v 500 \
  ask1_p 1850.50 ask1_v 200 \
  ... \
  timestamp 1724396100000

# 设置过期
EXPIRE ths:l2:quote:600519 60

# 读取完整盘口
HGETALL ths:l2:quote:600519

# 只读取最新价
HGET ths:l2:quote:600519 last_price

# 读取买卖五档
HMGET ths:l2:quote:600519 bid1_p bid1_v bid2_p bid2_v ask1_p ask1_v
```

---

## 4. 逐笔成交流 (Stream)

```
Key: ths:l2:tick:{code}
   例如: ths:l2:tick:600519
Type: STREAM
MaxLen: 10000 (每支股票保留最近 10000 笔)
```

### Entry 格式

```
Field: {
  "t",          // 时间戳 (HHMMSSmmm 或 unix ms)
  "p",          // 价格
  "v",          // 成交量 (手)
  "d",          // 方向 (B=买/S=卖)
  "a",          // 成交金额 (可选)
}
```

### Redis 命令

```bash
# 追加逐笔成交
XADD ths:l2:tick:600519 * \
  t 1724396100000 \
  p 1850.50 \
  v 10 \
  d B

# 读取最近 100 笔
XREVRANGE ths:l2:tick:600519 + - COUNT 100

# 读取某个时间点之后的数据
XREAD BLOCK 1000 STREAMS ths:l2:tick:600519 $

# 查看流的长度
XLEN ths:l2:tick:600519

# 修剪流（保留最近 10000 条）
XTRIM ths:l2:tick:600519 MAXLEN 10000
```

---

## 5. 实时状态 (Hash)

```
Key: ths:l2:status:{code}
Type: HASH
TTL: 60 秒
```

### Field 定义

| Field | 类型 | 说明 |
|-------|------|------|
| connected | String | 连接状态: "1"/"0" |
| last_seq | Long | 最后消息序号 |
| tick_count | Long | 累计 tick 数 |
| quote_count | Long | 累计 quote 数 |
| last_update | Long | 最后更新时间(ms) |
| latency_ms | Int | 延迟(毫秒) |

---

## 6. 系统配置 (Hash)

```
Key: ths:l2:config
Type: HASH
```

### Field 定义

| Field | 说明 | 默认值 |
|-------|------|--------|
| server_host | 行情服务器 IP | "8.134.137.28" |
| server_port | 行情服务器端口 | "9528" |
| redis_host | Redis 地址 | "127.0.0.1" |
| redis_port | Redis 端口 | "6379" |
| batch_size | Pipeline 批量大小 | "50" |
| flush_interval_ms | 刷新间隔(ms) | "100" |
| reconnect_interval | 重连间隔(s) | "5" |

---

## 7. 发布订阅 (Pub/Sub)

### Channel 定义

```
Channel: ths:l2:quote:update
Payload: JSON {"code": "600519", "last_price": 1850.50, "ts": 1724396100000}

Channel: ths:l2:tick:update
Payload: JSON {"code": "600519", "price": 1850.50, "volume": 10, "direction": "B", "ts": ...}

Channel: ths:l2:pool:change
Payload: JSON {"action": "add", "code": "600667"}
```

### Redis 命令

```bash
# 订阅盘口更新
SUBSCRIBE ths:l2:quote:update

# 订阅 tick 更新
SUBSCRIBE ths:l2:tick:update

# 订阅股票池变更
SUBSCRIBE ths:l2:pool:change

# 发布更新
PUBLISH ths:l2:quote:update '{"code":"600519","last_price":1850.50}'
```

---

## 8. 写入策略

### Pipeline 批量写入

```python
# 每 100ms 或积累 50 条数据后批量写入
pipe = redis.pipeline()

for tick in tick_batch:
    pipe.xadd(f"ths:l2:tick:{tick['code']}", tick['fields'])

for quote in quote_batch:
    pipe.hset(f"ths:l2:quote:{quote['code']}", mapping=quote['fields'])
    pipe.expire(f"ths:l2:quote:{quote['code']}", 60)

pipe.execute()
```

### 写入频率

| 数据类型 | 触发条件 | 最大延迟 |
|---------|---------|---------|
| quote | 每帧到达 | < 100ms |
| tick | 每帧到达 | < 100ms |
| status | 每秒 | < 1s |
| pubsub | 每帧到达 | < 50ms |

---

## 9. 内存估算

| Key 类型 | 单支股票 | 500 支股票 |
|---------|---------|-----------|
| quote (Hash) | ~2KB | ~1MB |
| tick (Stream, 10K条) | ~2MB | ~1GB |
| status (Hash) | ~0.5KB | ~0.25MB |
| **总计** | **~2MB/股** | **~1GB** |

**建议**: 服务器内存 >= 8GB，Redis maxmemory = 4GB

---

## 10. 监控指标

```bash
# 查看订阅池大小
SCARD ths:l2:pool

# 查看所有活跃的 quote key
KEYS ths:l2:quote:*

# 查看 tick 流长度
XLEN ths:l2:tick:600519

# 查看内存使用
INFO memory

# 查看连接数
CLIENT LIST
```
