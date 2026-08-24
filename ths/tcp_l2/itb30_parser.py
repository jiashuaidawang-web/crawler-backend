#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
itb3.0 (10档盘口) 解析器

协议结构:
  [Magic(4)] [MsgID(8)] [Type(2)] [Zero(1)] [Seq(1)] [PayloadLen(4)] [Reserved(16)]
  [itb3.0\0(7)]
  [Count(4)] [Marker(4)] [Padding(4)] [HeaderFields...]
  [24 XX YY YY]... (field data)

字段类型 (推测):
  0x00: 股票代码 / 其他标识
  0x01-0x09: 买卖价格/成交量
  0x0a, 0x0d, 0x13, 0x22, 0x30, 0x31, 0x56, 0x59: 盘口数据
"""

import sys
import struct
import csv
import json
from datetime import datetime
from collections import defaultdict
from scapy.all import rdpcap, TCP, Raw, IP

PCAP_FILE = r"D:/stock/data/000001_1432.pcapng"


def parse_itb30_frame(data, pkt_idx=0, timestamp=0):
    """
    解析 itb3.0 帧

    返回: dict with parsed data or None
    """
    # 查找 itb3.0 标记
    marker_pos = data.find(b'itb3.0')
    if marker_pos < 0:
        return None

    result = {
        'pkt_idx': pkt_idx,
        'timestamp': timestamp,
        'frame_offset': marker_pos,
    }

    # 解析帧头
    if len(data) < 40:
        return None

    magic = data[0:4]
    if magic != b'\xfd\xfd\xfd\xfd':
        return None

    msg_id = data[4:12].decode('ascii', errors='replace')
    msg_type = struct.unpack_from('<H', data, 12)[0]
    seq = data[15]
    payload_len = struct.unpack_from('<I', data, 16)[0]

    result['msg_id'] = msg_id
    result['msg_type'] = f"0x{msg_type:04x}"
    result['seq'] = seq
    result['payload_len'] = payload_len

    # 解析数据区
    data_start = marker_pos + 7  # 'itb3.0' + null terminator
    if len(data) < data_start + 12:
        return None

    count = struct.unpack_from('<I', data, data_start)[0]
    marker = struct.unpack_from('<I', data, data_start + 4)[0]
    padding = struct.unpack_from('<I', data, data_start + 8)[0]

    result['count'] = count
    result['marker'] = marker

    # 解析 header 字段 (data_start+12 到第一个 0x24)
    header_bytes = data[data_start + 12:data_start + 28]
    result['header_raw'] = header_bytes.hex()

    # 解析 24 XX YY YY 字段
    data_area = data[data_start:]
    pos = 12  # 跳过 header
    fields = []
    field_types = defaultdict(list)

    while pos < len(data_area) - 3:
        if data_area[pos] == 0x24:
            field_type = data_area[pos + 1]
            field_val = struct.unpack_from('<H', data_area, pos + 2)[0]
            fields.append({
                'offset': pos,
                'type': field_type,
                'value': field_val,
            })
            field_types[field_type].append(field_val)
            pos += 4
        else:
            pos += 1

    result['fields'] = fields
    result['field_types'] = {f"0x{k:02x}": v for k, v in field_types.items()}

    # 尝试提取股票代码 (搜索 0x00000001)
    stock_code = None
    for i in range(len(data) - 4):
        val = struct.unpack_from('<I', data, i)[0]
        if val == 1:
            stock_code = "0000001"
            break
    result['stock_code'] = stock_code

    # 尝试提取价格数据
    # 根据观察, 字段值在 1000-1200 范围内的可能是价格 (x100)
    prices = []
    volumes = []
    for f in fields:
        if 1000 <= f['value'] <= 1200:
            prices.append(f['value'])
        elif 100 <= f['value'] <= 10000:
            volumes.append(f['value'])

    result['price_candidates'] = prices
    result['volume_candidates'] = volumes

    return result


def extract_itb30_from_pcap(pcap_file):
    """从 pcap 文件中提取所有 itb3.0 帧"""
    packets = rdpcap(pcap_file)
    frames = []

    for i, pkt in enumerate(packets):
        if pkt.haslayer(TCP) and pkt.haslayer(IP) and pkt.haslayer(Raw):
            tcp = pkt[TCP]
            if tcp.sport == 9528:
                raw = pkt[Raw].load
                if b'itb3.0' in raw:
                    timestamp = float(pkt.time) if hasattr(pkt, 'time') else 0
                    parsed = parse_itb30_frame(raw, i, timestamp)
                    if parsed:
                        frames.append(parsed)

    return frames


def save_to_csv(frames, output_file):
    """保存解析结果到 CSV"""
    with open(output_file, 'w', newline='', encoding='utf-8') as f:
        writer = csv.writer(f)
        writer.writerow([
            'pkt_idx', 'timestamp', 'msg_id', 'seq', 'count',
            'stock_code', 'price_candidates', 'volume_candidates',
            'field_types', 'header_raw'
        ])

        for frame in frames:
            writer.writerow([
                frame['pkt_idx'],
                frame['timestamp'],
                frame['msg_id'],
                frame['seq'],
                frame['count'],
                frame.get('stock_code', ''),
                json.dumps(frame.get('price_candidates', [])),
                json.dumps(frame.get('volume_candidates', [])),
                json.dumps(frame.get('field_types', {})),
                frame.get('header_raw', ''),
            ])

    print(f"Saved {len(frames)} frames to {output_file}")


def save_raw_hex(frames_data, output_file):
    """保存原始 hex 数据到 CSV"""
    with open(output_file, 'w', newline='', encoding='utf-8') as f:
        writer = csv.writer(f)
        writer.writerow(['pkt_idx', 'timestamp', 'frame_len', 'hex_data'])

        for pkt_idx, timestamp, data in frames_data:
            writer.writerow([
                pkt_idx,
                timestamp,
                len(data),
                data.hex(),
            ])

    print(f"Saved {len(frames_data)} raw frames to {output_file}")


def analyze_itb30_structure(frames):
    """分析 itb3.0 结构"""
    print(f"\n=== itb3.0 Structure Analysis ===")
    print(f"Total frames: {len(frames)}")

    # 统计字段类型
    all_field_types = defaultdict(list)
    for frame in frames:
        for f in frame.get('fields', []):
            all_field_types[f['type']].append(f['value'])

    print(f"\nField type distribution:")
    for ftype in sorted(all_field_types.keys()):
        vals = all_field_types[ftype]
        unique_vals = set(vals)
        print(f"  Type 0x{ftype:02x}: {len(vals)} occurrences, {len(unique_vals)} unique values")
        if len(unique_vals) <= 10:
            print(f"    Values: {sorted(unique_vals)}")
        else:
            print(f"    Sample: {sorted(unique_vals)[:10]}...")

    # 检查价格变化
    all_prices = []
    for frame in frames:
        all_prices.extend(frame.get('price_candidates', []))

    if all_prices:
        print(f"\nPrice candidates: {len(all_prices)} total")
        print(f"  Min: {min(all_prices)} ({min(all_prices)/100:.2f})")
        print(f"  Max: {max(all_prices)} ({max(all_prices)/100:.2f})")
        print(f"  Unique: {sorted(set(all_prices))}")

    # 检查成交量变化
    all_volumes = []
    for frame in frames:
        all_volumes.extend(frame.get('volume_candidates', []))

    if all_volumes:
        print(f"\nVolume candidates: {len(all_volumes)} total")
        print(f"  Min: {min(all_volumes)}")
        print(f"  Max: {max(all_volumes)}")
        print(f"  Unique count: {len(set(all_volumes))}")


def main(pcap_file):
    print("Extracting itb3.0 frames from pcap...")
    frames = extract_itb30_from_pcap(pcap_file)

    if not frames:
        print("No itb3.0 frames found!")
        return

    print(f"Found {len(frames)} itb3.0 frames")

    # 分析结构
    analyze_itb30_structure(frames)

    # 保存解析结果
    csv_file = pcap_file.replace('.pcapng', '_itb30_parsed.csv')
    save_to_csv(frames, csv_file)

    # 保存原始 hex 数据
    packets = rdpcap(pcap_file)
    raw_frames = []
    for i, pkt in enumerate(packets):
        if pkt.haslayer(TCP) and pkt.haslayer(IP) and pkt.haslayer(Raw):
            tcp = pkt[TCP]
            if tcp.sport == 9528:
                raw = pkt[Raw].load
                if b'itb3.0' in raw:
                    timestamp = float(pkt.time) if hasattr(pkt, 'time') else 0
                    raw_frames.append((i, timestamp, raw))

    hex_file = pcap_file.replace('.pcapng', '_itb30_raw.csv')
    save_raw_hex(raw_frames, hex_file)

    # 打印前 5 个帧的详细信息
    print(f"\n=== First 5 frames detail ===")
    for frame in frames[:5]:
        print(f"\nPacket {frame['pkt_idx']}:")
        print(f"  MsgID: {frame['msg_id']}, Seq: {frame['seq']}, Count: {frame['count']}")
        print(f"  Stock: {frame.get('stock_code', 'N/A')}")
        print(f"  Prices: {frame.get('price_candidates', [])}")
        print(f"  Volumes: {frame.get('volume_candidates', [])}")
        print(f"  Fields: {len(frame.get('fields', []))}")
        for f in frame.get('fields', [])[:10]:
            print(f"    Type 0x{f['type']:02x}: {f['value']} (0x{f['value']:04x})")


if __name__ == "__main__":
    pcap_file = sys.argv[1] if len(sys.argv) > 1 else PCAP_FILE
    main(pcap_file)
