# 同花顺 L2 实时数据契约

## 概述

- **数据源**: 同花顺 App (com.hexin.plat.android) 通过 mitmproxy 抓包
- **传输方式**: Redis Stream
- **Stream Key**: `ths_l2_realtime`
- **延迟目标**: 秒级 (< 1秒)
- **数据格式**: JSON (XADD field 名为 "data")

---

## 数据类型 (data_type)

| 类型 | 说明 | API 路径关键词 |
|------|------|---------------|
| `snapshot` | 实时行情快照 | `multi_last_snapshot` |
| `depth` | L2 十档盘口 | `depth` |
| `tick` | L2 逐笔成交 | `tick` |
| `trend` | 分时走势 | `single_trend`, `trade_time` |

---

## 基础结构 (所有类型共用)

```json
{
    "timestamp": "2026-08-22T12:30:45.123456",
    "data_type": "snapshot",
    "stock_code": "000001",
    "stock_name": "平安银行",
    "market": "0",
    "raw": { ... }
}
```

| 字段 | 类型 | 说明 |
|------|------|------|
| timestamp | string | ISO8601 抓取时间 |
| data_type | string | 数据类型 |
| stock_code | string | 股票代码 |
| stock_name | string | 股票名称 |
| market | string | 0=深圳 1=上海 |
| raw | object | 原始 API 响应数据 |

---

## 1. snapshot - 实时行情快照

```json
{
    "timestamp": "2026-08-22T12:30:45.123456",
    "data_type": "snapshot",
    "stock_code": "000001",
    "stock_name": "平安银行",
    "market": "0",
    "last_price": 12.50,
    "open": 12.30,
    "high": 12.80,
    "low": 12.20,
    "prev_close": 12.40,
    "volume": 1234567,
    "amount": 15432100.00,
    "change": 0.10,
    "change_pct": 0.81,
    "turnover": 1.23,
    "pe": 15.6,
    "amplitude": 4.84,
    "market_cap": 150000000000,
    "float_cap": 120000000000,
    "pb": 1.2,
    "limit_up": 13.64,
    "limit_down": 11.16,
    "raw": { ... }
}
```

| 字段 | 类型 | 说明 |
|------|------|------|
| last_price | number | 最新价 |
| open | number | 开盘价 |
| high | number | 最高价 |
| low | number | 最低价 |
| prev_close | number | 昨收价 |
| volume | integer | 成交量(手) |
| amount | number | 成交额(元) |
| change | number | 涨跌额 |
| change_pct | number | 涨跌幅% |
| turnover | number | 换手率% |
| pe | number | 市盈率 |
| amplitude | number | 振幅% |
| market_cap | integer | 总市值 |
| float_cap | integer | 流通市值 |
| pb | number | 市净率 |
| limit_up | number | 涨停价 |
| limit_down | number | 跌停价 |

---

## 2. depth - L2 十档盘口

```json
{
    "timestamp": "2026-08-22T12:30:45.123456",
    "data_type": "depth",
    "stock_code": "000001",
    "stock_name": "平安银行",
    "market": "0",
    "bid_prices": [12.50, 12.49, 12.48, 12.47, 12.46, 12.45, 12.44, 12.43, 12.42, 12.41],
    "bid_volumes": [1000, 2000, 1500, 3000, 2500, 1800, 2200, 1600, 1400, 1900],
    "ask_prices": [12.51, 12.52, 12.53, 12.54, 12.55, 12.56, 12.57, 12.58, 12.59, 12.60],
    "ask_volumes": [800, 1200, 1600, 2000, 1800, 1500, 2100, 1700, 1300, 1100],
    "bid_total": 25000,
    "ask_total": 18000,
    "weighted_avg_bid": 12.47,
    "weighted_avg_ask": 12.53,
    "raw": { ... }
}
```

| 字段 | 类型 | 说明 |
|------|------|------|
| bid_prices | number[10] | 买一到买十价格 |
| bid_volumes | integer[10] | 买一到买十挂单量(手) |
| ask_prices | number[10] | 卖一到卖十价格 |
| ask_volumes | integer[10] | 卖一到卖十挂单量(手) |
| bid_total | integer | 买盘总量 |
| ask_total | integer | 卖盘总量 |
| weighted_avg_bid | number | 加权平均买价 |
| weighted_avg_ask | number | 加权平均卖价 |

---

## 3. tick - L2 逐笔成交

```json
{
    "timestamp": "2026-08-22T12:30:45.123456",
    "data_type": "tick",
    "stock_code": "000001",
    "stock_name": "平安银行",
    "market": "0",
    "tick_time": "12:30:45.500",
    "price": 12.50,
    "volume": 100,
    "amount": 1250.00,
    "direction": "B",
    "trade_type": "0",
    "raw": { ... }
}
```

| 字段 | 类型 | 说明 |
|------|------|------|
| tick_time | string | 成交时间(毫秒级) |
| price | number | 成交价 |
| volume | integer | 成交量(股) |
| amount | number | 成交金额 |
| direction | string | B=主动买入 S=主动卖出 |
| trade_type | string | 0=成交 1=撤单 2=其他 |

---

## 4. trend - 分时走势

```json
{
    "timestamp": "2026-08-22T12:30:45.123456",
    "data_type": "trend",
    "stock_code": "000001",
    "stock_name": "平安银行",
    "market": "0",
    "avg_price": 12.48,
    "price_change": 0.10,
    "volume": 1234567,
    "amount": 15432100.00,
    "raw": { ... }
}
```

| 字段 | 类型 | 说明 |
|------|------|------|
| avg_price | number | 分时均价 |
| price_change | number | 涨跌额 |
| volume | integer | 分时成交量 |
| amount | number | 分时成交额 |

---

## Redis 消费示例

```python
import redis
import json

r = redis.Redis(host='127.0.0.1', port=6379, decode_responses=True)

# 方式1: 批量读取历史
def read_history(count=100):
    entries = r.xrange('ths_l2_realtime', count=count)
    for msg_id, msg_data in entries:
        data = json.loads(msg_data['data'])
        yield msg_id, data

# 方式2: 实时阻塞读取
def read_realtime(block_ms=0):
    """block_ms=0 表示永久阻塞等待新数据"""
    last_id = '0'
    while True:
        resp = r.xread({'ths_l2_realtime': last_id}, block=block_ms, count=100)
        for stream_name, messages in resp:
            for msg_id, msg_data in messages:
                last_id = msg_id
                data = json.loads(msg_data['data'])
                yield msg_id, data

# 方式3: 按数据类型过滤消费
def read_by_type(data_type, block_ms=0):
    """只消费指定类型: snapshot|depth|tick|trend"""
    for msg_id, data in read_realtime(block_ms):
        if data.get('data_type') == data_type:
            yield msg_id, data

# 使用示例
if __name__ == '__main__':
    print("=== 实时 L2 数据 ===")
    for msg_id, data in read_realtime():
        stock = data.get('stock_code', '?')
        dtype = data.get('data_type', '?')
        ts = data.get('timestamp', '?')
        print(f"[{ts}] {dtype} | {stock}")
```

---

## 注意事项

1. **raw 字段**: 保留完整原始 API 响应，方便后续解析新字段
2. **timestamp**: 是抓取时间，不是交易时间（交易时间在各类型的子字段中）
3. **stock_name**: 从请求体或响应中解析，可能为空
4. **market**: 0=深圳 1=上海（同花顺内部编码）
5. **数据去重**: Redis Stream 天然按 msg_id 递增，消费端需自行去重
