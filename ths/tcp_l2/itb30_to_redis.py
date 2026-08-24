#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
itb3.0 (10档盘口) 解析器 - 从 pcap 提取并写入 Redis

功能:
1. 从 pcap 文件提取 itb3.0 帧
2. 解析买卖十档数据
3. 写入 Redis (ths:l2:quote:0000001)

Redis 数据结构:
  Key: ths:l2:quote:{stock_code}
  Type: Hash
  Fields:
    - prices: 价格列表 (逗号分隔)
    - volumes: 成交量列表 (逗号分隔)
    - field_types: 字段类型摘要 (JSON)
    - timestamp: 更新时间
    - frame_count: 帧计数
"""

import sys
import struct
import json
import time
from datetime import datetime
from collections import defaultdict
from scapy.all import rdpcap, TCP, Raw, IP

# Redis 配置
REDIS_HOST = "127.0.0.1"
REDIS_PORT = 6379
REDIS_DB = 0

PCAP_FILE = r"D:/stock/data/000001_1432.pcapng"
STOCK_CODE = "0000001"

# itb3.0 标记
ITB_MARKER = b'itb3.0'


def parse_itb30_frame(data, pkt_idx=0, timestamp=0):
    """
    解析 itb3.0 帧

    返回: dict with parsed data or None
    """
    itb_pos = data.find(ITB_MARKER)
    if itb_pos < 0:
        return None

    data_start = itb_pos + 7  # 'itb3.0' + null terminator
    if len(data) < data_start + 12:
        return None

    count = struct.unpack_from('<I', data, data_start)[0]

    # 搜索第一个 24 字节 (字段标记)
    field_start = data_start + 12  # skip count + marker + padding
    while field_start < len(data) and data[field_start] != 0x24:
        field_start += 1

    # 解析所有 24 XX YY YY 字段
    pos = field_start
    fields = []
    field_types = defaultdict(list)

    while pos < len(data) - 3:
        if data[pos] == 0x24:
            field_type = data[pos + 1]
            field_val = struct.unpack_from('<H', data, pos + 2)[0]
            fields.append({
                'offset': pos - data_start,
                'type': field_type,
                'value': field_val,
            })
            field_types[field_type].append(field_val)
            pos += 4
        else:
            break

    # 提取价格 (1000-1200 范围, 除以100得到实际价格)
    prices = []
    volumes = []
    for f in fields:
        if 1000 <= f['value'] <= 1200:
            prices.append(f['value'])
        elif 100 <= f['value'] <= 10000 and f['value'] not in [8192, 8326]:
            volumes.append(f['value'])

    return {
        'pkt_idx': pkt_idx,
        'timestamp': timestamp,
        'count': count,
        'fields': fields,
        'field_types': {f"0x{k:02x}": v for k, v in field_types.items()},
        'prices': prices,
        'volumes': volumes,
        'price_str': ",".join([f"{p/100:.2f}" for p in prices]),
        'volume_str': ",".join([str(v) for v in volumes]),
    }


def extract_itb30_frames(pcap_file):
    """从 pcap 文件中提取所有 itb3.0 帧"""
    packets = rdpcap(pcap_file)
    frames = []

    for i, pkt in enumerate(packets):
        if pkt.haslayer(TCP) and pkt.haslayer(IP) and pkt.haslayer(Raw):
            tcp = pkt[TCP]
            if tcp.sport == 9528:
                raw = pkt[Raw].load
                if ITB_MARKER in raw:
                    timestamp = float(pkt.time) if hasattr(pkt, 'time') else 0
                    parsed = parse_itb30_frame(raw, i, timestamp)
                    if parsed:
                        frames.append(parsed)

    return frames


def save_to_redis(frames, stock_code):
    """保存解析结果到 Redis"""
    import redis

    r = redis.Redis(host=REDIS_HOST, port=REDIS_PORT, db=REDIS_DB)
    r.ping()
    print(f"Redis 连接成功: {REDIS_HOST}:{REDIS_PORT}")

    # 保存每一帧
    for idx, frame in enumerate(frames):
        key = f"ths:l2:quote:{stock_code}"

        # 使用 hash 存储
        data = {
            "stock_code": stock_code,
            "pkt_idx": str(frame['pkt_idx']),
            "frame_seq": str(idx),
            "count": str(frame['count']),
            "prices": frame['price_str'],
            "volumes": frame['volume_str'],
            "price_raw": ",".join([str(p) for p in frame['prices']]),
            "volume_raw": ",".join([str(v) for v in frame['volumes']]),
            "field_types": json.dumps(frame['field_types']),
            "field_count": str(len(frame['fields'])),
            "timestamp": str(frame['timestamp']),
            "update_time": datetime.now().strftime("%Y-%m-%d %H:%M:%S"),
        }

        r.hset(key, mapping=data)

        # 同时保存到 list (历史记录)
        list_key = f"ths:l2:quote_history:{stock_code}"
        r.lpush(list_key, json.dumps({
            "pkt_idx": frame['pkt_idx'],
            "prices": frame['price_str'],
            "volumes": frame['volume_str'],
            "timestamp": str(frame['timestamp']),
        }))
        r.ltrim(list_key, 0, 99)  # 只保留最近 100 条

        print(f"  Frame {idx}: pkt={frame['pkt_idx']}, prices={frame['price_str']}, volumes={frame['volume_str']}")

    # 保存统计信息
    stats_key = f"ths:l2:stats:{stock_code}"
    r.hset(stats_key, mapping={
        "total_frames": str(len(frames)),
        "stock_code": stock_code,
        "last_update": datetime.now().strftime("%Y-%m-%d %H:%M:%S"),
        "pcap_file": PCAP_FILE,
    })

    print(f"\n保存完成: {len(frames)} 帧写入 Redis")
    print(f"  Key: ths:l2:quote:{stock_code}")
    print(f"  History Key: ths:l2:quote_history:{stock_code}")
    print(f"  Stats Key: ths:l2:stats:{stock_code}")


def print_summary(frames):
    """打印摘要"""
    print(f"\n=== itb3.0 解析摘要 ===")
    print(f"总帧数: {len(frames)}")

    if not frames:
        return

    # 统计所有价格
    all_prices = set()
    all_volumes = set()
    for frame in frames:
        all_prices.update(frame['prices'])
        all_volumes.update(frame['volumes'])

    print(f"唯一价格: {sorted(all_prices)}")
    print(f"  作为价格: {[f'{p/100:.2f}' for p in sorted(all_prices)]}")
    print(f"唯一成交量: {sorted(all_volumes)}")

    # 打印前 5 帧详情
    print(f"\n前 5 帧详情:")
    for frame in frames[:5]:
        print(f"  pkt={frame['pkt_idx']}, count={frame['count']}, "
              f"prices={frame['price_str']}, volumes={frame['volume_str']}")


def main():
    pcap_file = sys.argv[1] if len(sys.argv) > 1 else PCAP_FILE
    stock_code = sys.argv[2] if len(sys.argv) > 2 else STOCK_CODE

    print(f"正在解析 {pcap_file} ...")
    frames = extract_itb30_frames(pcap_file)

    if not frames:
        print("未找到 itb3.0 帧!")
        return

    print(f"找到 {len(frames)} 个 itb3.0 帧")

    # 打印摘要
    print_summary(frames)

    # 保存到 Redis
    print(f"\n正在写入 Redis ...")
    save_to_redis(frames, stock_code)

    # 验证 Redis 数据
    import redis
    r = redis.Redis(host=REDIS_HOST, port=REDIS_PORT, db=REDIS_DB)
    key = f"ths:l2:quote:{stock_code}"
    data = r.hgetall(key)
    print(f"\n=== Redis 验证 ===")
    print(f"Key: {key}")
    for k, v in data.items():
        print(f"  {k.decode()}: {v.decode()}")


if __name__ == "__main__":
    main()
