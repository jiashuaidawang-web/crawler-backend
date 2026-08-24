#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
寻找真正的逐笔成交数据

关键发现:
1. B@978, S@224, S@247 都在 itb3.0 帧中 (10档盘口, 不是逐笔)
2. 价格和成交量在不同帧中
3. 逐笔成交可能在 "unknown" 帧中

策略:
1. 排除 itb3.0/tb3.0/tb1.0/JiTu/cv3.0/JSON 帧
2. 在剩余帧中搜索方向字节 B/S
3. 分析包含方向字节的帧的结构
"""

import sys
import struct
from collections import Counter, defaultdict
from scapy.all import rdpcap, TCP, Raw, IP

PCAP_FILE = r"D:/stock/data/000001_1432.pcapng"


def get_all_s2c_frames(pcap_file):
    """获取所有 S->C 帧"""
    packets = rdpcap(pcap_file)
    s2c_frames = []
    for i, pkt in enumerate(packets):
        if pkt.haslayer(TCP) and pkt.haslayer(IP) and pkt.haslayer(Raw):
            tcp = pkt[TCP]
            if tcp.sport == 9528:
                raw = pkt[Raw].load
                s2c_frames.append({
                    'pkt_idx': i,
                    'data': raw,
                    'len': len(raw),
                    'time': float(pkt.time) if hasattr(pkt, 'time') else 0,
                })
    return s2c_frames


def classify_frame(data):
    """分类帧类型"""
    if b'itb3.0' in data:
        return 'itb3.0'
    if b'tb3.0' in data:
        return 'tb3.0'
    if b'tb1.0' in data:
        return 'tb1.0'
    if b'ltb1.0' in data:
        return 'ltb1.0'
    if b'JiTu' in data:
        return 'JiTu'
    if b'cv3.0' in data:
        return 'cv3.0'
    if data[:4] == b'\xfd\xfd\xfd\xfd':
        return 'magic_unknown'
    if b'{' in data or b'"' in data:
        return 'json_like'
    return 'unknown'


def find_tick_frames(pcap_file):
    """寻找可能的逐笔成交帧"""
    s2c_frames = get_all_s2c_frames(pcap_file)

    # 分类帧
    classified = defaultdict(list)
    for frame in s2c_frames:
        frame_type = classify_frame(frame['data'])
        classified[frame_type].append(frame)

    print("Frame type distribution:")
    for frame_type, frames in sorted(classified.items(), key=lambda x: -len(x[1])):
        print(f"  {frame_type}: {len(frames)} frames")

    # 排除已知的非逐笔帧类型
    exclude_types = ['itb3.0', 'tb3.0', 'tb1.0', 'ltb1.0', 'JiTu', 'cv3.0']
    candidate_frames = []
    for frame_type, frames in classified.items():
        if frame_type not in exclude_types:
            candidate_frames.extend(frames)

    print(f"\nCandidate frames (excluding known types): {len(candidate_frames)}")

    # 在候选帧中搜索方向字节
    frames_with_dir = []
    for frame in candidate_frames:
        data = frame['data']
        b_count = data.count(0x42)
        s_count = data.count(0x53)
        if b_count > 0 or s_count > 0:
            frames_with_dir.append({
                **frame,
                'b_count': b_count,
                's_count': s_count,
                'b_positions': [j for j, b in enumerate(data) if b == 0x42],
                's_positions': [j for j, b in enumerate(data) if b == 0x53],
            })

    print(f"Candidate frames with direction bytes: {len(frames_with_dir)}")

    # 分析包含方向字节的帧
    for frame in frames_with_dir[:10]:
        analyze_tick_frame(frame)

    return frames_with_dir


def analyze_tick_frame(frame):
    """分析可能的逐笔成交帧"""
    data = frame['data']

    print(f"\n{'='*70}")
    print(f"Packet {frame['pkt_idx']} ({frame['len']}B)")
    print(f"B count: {frame['b_count']}, S count: {frame['s_count']}")
    print(f"{'='*70}")

    # 打印完整 hex dump
    for j in range(0, len(data), 32):
        hex_str = data[j:j+32].hex()
        formatted = ' '.join(hex_str[k:k+2] for k in range(0, len(hex_str), 2))
        ascii_str = ''.join(chr(b) if 32 <= b < 127 else '.' for b in data[j:j+32])
        print(f"  {j:04d}: {formatted}  {ascii_str}")

    # 搜索特征常量
    vol_pattern = struct.pack('<H', 10386)  # 92 28
    px_pattern = struct.pack('<H', 1141)    # 75 04

    vol_positions = []
    px_positions = []
    pos = 0
    while True:
        idx = data.find(vol_pattern, pos)
        if idx == -1:
            break
        vol_positions.append(idx)
        pos = idx + 1

    pos = 0
    while True:
        idx = data.find(px_pattern, pos)
        if idx == -1:
            break
        px_positions.append(idx)
        pos = idx + 1

    print(f"\n  Volume (92 28) at: {vol_positions}")
    print(f"  Price (75 04) at: {px_positions}")

    # 分析方向字节的分布
    if frame['b_positions']:
        analyze_direction_distribution(frame['b_positions'], 'B')
    if frame['s_positions']:
        analyze_direction_distribution(frame['s_positions'], 'S')


def analyze_direction_distribution(positions, label):
    """分析方向字节的分布"""
    if len(positions) < 2:
        print(f"  {label} positions: {positions}")
        return

    # 计算间隔
    intervals = [positions[j+1] - positions[j] for j in range(len(positions)-1)]
    interval_counter = Counter(intervals)

    print(f"  {label} positions: {positions}")
    print(f"  {label} intervals: {interval_counter.most_common(5)}")

    # 如果间隔固定, 说明是固定大小的记录
    if len(interval_counter) == 1:
        record_size = intervals[0]
        print(f"  Fixed interval: {record_size} bytes (possible record size)")

        # 分析记录结构
        for pos in positions[:3]:
            record_start = pos - (pos % record_size)
            record = data[record_start:record_start + record_size]
            hex_str = ' '.join(f'{b:02x}' for b in record)
            dir_offset_in_record = pos - record_start
            print(f"    Record at {record_start}: {hex_str}")
            print(f"    Direction at offset {dir_offset_in_record} in record")


def find_price_in_tick_frames(pcap_file):
    """在可能的逐笔帧中搜索价格"""
    s2c_frames = get_all_s2c_frames(pcap_file)

    # 排除已知的非逐笔帧类型
    exclude_types = ['itb3.0', 'tb3.0', 'tb1.0', 'ltb1.0', 'JiTu', 'cv3.0']

    # 搜索价格 11.41 附近的所有可能编码
    price_targets = []
    for scale in [1, 10, 100, 1000, 10000]:
        val = int(11.41 * scale)
        if val < 65536:
            price_targets.append((f'px_x{scale}_le', struct.pack('<H', val)))
            price_targets.append((f'px_x{scale}_be', struct.pack('>H', val)))
        if val < 4294967296:
            price_targets.append((f'px_x{scale}_le32', struct.pack('<I', val)))
            price_targets.append((f'px_x{scale}_be32', struct.pack('>I', val)))

    # 在候选帧中搜索
    results = defaultdict(list)
    for frame in s2c_frames:
        frame_type = classify_frame(frame['data'])
        if frame_type in exclude_types:
            continue

        data = frame['data']
        for name, pattern in price_targets:
            pos = 0
            while True:
                idx = data.find(pattern, pos)
                if idx == -1:
                    break
                results[name].append((frame['pkt_idx'], idx))
                pos = idx + 1

    print("\nPrice search results in candidate frames:")
    for name, hits in sorted(results.items(), key=lambda x: -len(x[1])):
        if hits:
            print(f"  {name}: {len(hits)} hits")
            for pkt_idx, offset in hits[:5]:
                print(f"    pkt={pkt_idx}, offset={offset}")


def find_volume_in_tick_frames(pcap_file):
    """在可能的逐笔帧中搜索成交量"""
    s2c_frames = get_all_s2c_frames(pcap_file)

    # 排除已知的非逐笔帧类型
    exclude_types = ['itb3.0', 'tb3.0', 'tb1.0', 'ltb1.0', 'JiTu', 'cv3.0']

    # 搜索成交量 10386 附近的所有可能编码
    vol_targets = []
    for val in [10386, 103860, 1038600]:
        vol_targets.append((f'vol_{val}_le', struct.pack('<H', val) if val < 65536 else struct.pack('<I', val)))
        vol_targets.append((f'vol_{val}_be', struct.pack('>H', val) if val < 65536 else struct.pack('>I', val)))

    # 在候选帧中搜索
    results = defaultdict(list)
    for frame in s2c_frames:
        frame_type = classify_frame(frame['data'])
        if frame_type in exclude_types:
            continue

        data = frame['data']
        for name, pattern in vol_targets:
            pos = 0
            while True:
                idx = data.find(pattern, pos)
                if idx == -1:
                    break
                results[name].append((frame['pkt_idx'], idx))
                pos = idx + 1

    print("\nVolume search results in candidate frames:")
    for name, hits in sorted(results.items(), key=lambda x: -len(x[1])):
        if hits:
            print(f"  {name}: {len(hits)} hits")
            for pkt_idx, offset in hits[:5]:
                print(f"    pkt={pkt_idx}, offset={offset}")


def main(pcap_file):
    # 1. 寻找可能的逐笔成交帧
    find_tick_frames(pcap_file)

    # 2. 在候选帧中搜索价格
    find_price_in_tick_frames(pcap_file)

    # 3. 在候选帧中搜索成交量
    find_volume_in_tick_frames(pcap_file)


if __name__ == "__main__":
    pcap_file = sys.argv[1] if len(sys.argv) > 1 else PCAP_FILE
    main(pcap_file)
