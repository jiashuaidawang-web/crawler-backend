# 同花顺 L2 实时数据 - Redis 数据结构

> 数据源: pcap 重放 (真实行情帧解析)
> 服务器: 139.159.194.69:9528
> 更新时间: 2026-08-23

---

## Key 总览

| Key 模式 | 类型 | 说明 |
|---|---|---|
| `ths:l2:meta` | Hash | 连接元数据 |
| `ths:l2:pool` | Set | 订阅股票池 |
| `ths:l2:events` | Stream | 系统事件(心跳等) |
| `ths:l2:tick:{code}` | Stream | 逐笔成交 (JiTu) |
| `ths:l2:quote:{code}` | Hash | 价格快照 (tb1.0) |

---

## 1. ths:l2:meta (Hash)

连接和统计元数据。

```
HGETALL ths:l2:meta
```

| 字段 | 示例值 | 说明 |
|---|---|---|
| server | 139.159.194.69 | 服务器 IP |
| port | 9528 | 端口 |
| start_time | 2026-08-23 14:30:33 | 开始时间 |
| stocks | 002384,600519,... | 订阅股票列表 |
| last_update | 2026-08-23 14:30:34 | 最后更新时间 |
| total_frames | 161 | 总帧数 |
| total_ticks | 8 | 总 tick 数 |
| total_tb10 | 91 | 总 tb10 数 |

---

## 2. ths:l2:pool (Set)

当前订阅的股票代码集合。

```
SMEMBERS ths:l2:pool
```

返回: `{"002384", "600519", "000001", "300750", "600667"}`

---

## 3. ths:l2:events (Stream)

系统事件流，包括心跳、连接状态等。

```
XRANGE ths:l2:events - +
```

每条记录 fields:

| 字段 | 类型 | 说明 |
|---|---|---|
| data | JSON字符串 | 事件数据 |

事件 JSON 结构:
```json
{
  "eventData": {"timeStamp": "1787459533129"},
  "eventID": "00000002",
  "_recv_time": "2026-08-23 14:30:34"
}
```

eventID 说明:
- `00000002` = 心跳

---

## 4. ths:l2:tick:{code} (Stream)

逐笔成交数据 (JiTu 格式解析)。

```
XRANGE ths:l2:tick:002384 - +
XREVRANGE ths:l2:tick:002384 + - COUNT 10   // 最新10条
XLEN ths:l2:tick:002384                       // 总数
```

每条记录 fields:

| 字段 | 类型 | 示例 | 说明 |
|---|---|---|---|
| t | string | 605000 | 时间 (毫秒，从0点开始) |
| p | string | 0.0 | 价格 (⚠️ 当前为0，JiTu格式不含价格) |
| v | string | 0 | 成交量 (⚠️ 当前为0) |
| d | string | S | 方向: S=卖出, B=买入 |
| a | string | 0.0 | 金额 (⚠️ 当前为0) |

**消费端 Python 示例:**
```python
import redis.asyncio as aioredis

r = aioredis.Redis(host='localhost', port=6379, db=0)

# 读取最新 tick
msgs = await r.xrevrange('ths:l2:tick:002384', count=10)
for msg_id, fields in msgs:
    time_ms = int(fields[b't'])
    direction = fields[b'd'].decode()  # 'S' or 'B'
    hours = time_ms // 3600000
    minutes = (time_ms % 3600000) // 60000
    seconds = (time_ms % 60000) // 1000
    ms = time_ms % 1000
    print(f"{hours:02d}:{minutes:02d}:{seconds:02d}.{ms:03d} {direction}")
```

---

## 5. ths:l2:quote:{code} (Hash)

价格快照数据 (tb1.0 二进制格式解析)。

```
HGETALL ths:l2:quote:002384
```

| 字段 | 类型 | 示例 | 说明 |
|---|---|---|---|
| code | string | 002384 | 股票代码 |
| tb10_prices | string | 201.19 | 价格 (逗号分隔) |
| tb10_volumes | string | 23500,2405 | 成交量 (逗号分隔) |
| tb10_strings | string | 002384,201.19 | 原始字符串列表 |
| tb10_count | string | 41 | 结构体数量 |
| tb10_data_len | string | 397 | 数据区长度 |
| timestamp | string | 2972614 | 时间戳 |
| update_time | string | 2026-08-23 14:30:34 | 更新时间 |

**消费端 Python 示例:**
```python
quote = await r.hgetall('ths:l2:quote:002384')
prices = quote[b'tb10_prices'].decode().split(',')
volumes = quote[b'tb10_volumes'].decode().split(',')
print(f"价格: {prices}")
print(f"成交量: {volumes}")
```

---

## 已知问题 & 注意事项

### ⚠️ 1. Tick 价格缺失 (p=0.0)
JiTu 格式只含方向和时间，不含价格和金额。价格需要从 tb1.0 获取。
- **方向**: `S` = 卖出(Tu), `B` = 买入(Ji)
- **时间**: 毫秒，从当日 0 点起算 (如 605000 = 10:05:00.000)

### ⚠️ 2. tb1.0 字段含义待确认
tb10_prices 提取到的价格，哪个对应最新价/买一/卖一，还需要对照行情软件确认。

### ⚠️ 3. itb3.0 (十档盘口) 未解析
十档盘口数据格式尚未逆向，当前解析返回 None。

### ⚠️ 4. 数据时间
- pcap 重放数据的时间戳是抓包时的真实时间
- 正式运行时，tick 时间是当日毫秒数，quote 的 timestamp 是相对时间

---

## 实时数据接入方式

正式运行时 (周一至周五 9:30-15:00)，数据会自动推送。消费端可以用以下方式:

### 方式 1: 轮询
```python
# 每 100ms 检查新 tick
while True:
    msgs = await r.xread({'ths:l2:tick:002384': '$'}, block=100, count=10)
    # 处理...
```

### 方式 2: Block 读取 (推荐)
```python
# 阻塞等待新数据
last_id = '0'
while True:
    result = await r.xread(
        {'ths:l2:tick:002384': last_id},
        block=1000,
        count=100
    )
    for stream_name, msgs in result:
        for msg_id, fields in msgs:
            last_id = msg_id
            # 处理 tick...
```

### 方式 3: Consumer Group (多消费者)
```python
# 创建 consumer group
await r.xgroup_create('ths:l2:tick:002384', 'consumer_group', id='0')

# 消费
while True:
    result = await r.xreadgroup(
        'consumer_group', 'consumer_1',
        {'ths:l2:tick:002384': '>'},
        block=1000,
        count=100
    )
    for stream_name, msgs in result:
        for msg_id, fields in msgs:
            # 处理...
            await r.xack('ths:l2:tick:002384', 'consumer_group', msg_id)
```
