#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
深度分析 tick 数据帧结构

关键发现:
1. 'B' 在 offset 978 出现 30 次 (前后字节恒定)
2. 'S' 在 offset 224 出现 30 次 (前后字节恒定)
3. 价格 1141 (0x0475) 在 pkt=103254@184, pkt=123078@182
4. 成交量 10386 (0x2892) 在 pkt=4803@190, pkt=1864@831

假设: tick 数据可能分布在不同类型的帧中
- cv3.0 帧: 包含成交量
- tb3.0 帧: 包含价格
- 其他帧: 包含方向和時間
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


def analyze_direction_frame(pcap_file):
    """分析方向字节所在的帧"""
    s2c_frames = get_all_s2c_frames(pcap_file)

    # 找 'B' 在 offset 978 的帧
    b_frames = []
    s_frames_224 = []
    s_frames_247 = []

    for frame in s2c_frames:
        data = frame['data']
        if len(data) > 978 and data[978] == 0x42:
            b_frames.append(frame)
        if len(data) > 224 and data[224] == 0x53:
            s_frames_224.append(frame)
        if len(data) > 247 and data[247] == 0x53:
            s_frames_247.append(frame)

    print(f"B@978 frames: {len(b_frames)}")
    print(f"S@224 frames: {len(s_frames_224)}")
    print(f"S@247 frames: {len(s_frames_247)}")

    # 分析 B@978 帧的完整结构
    if b_frames:
        analyze_single_frame_structure(b_frames[0], "B@978 sample")

    # 分析 S@224 帧的完整结构
    if s_frames_224:
        analyze_single_frame_structure(s_frames_224[0], "S@224 sample")

    # 分析 S@247 帧的完整结构
    if s_frames_247:
        analyze_single_frame_structure(s_frames_247[0], "S@247 sample")


def analyze_single_frame_structure(frame, label):
    """分析单个帧的完整结构"""
    data = frame['data']
    print(f"\n{'='*70}")
    print(f"{label}: Packet {frame['pkt_idx']} ({frame['len']}B)")
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

    # 搜索所有 B 和 S 的位置
    b_positions = [j for j, b in enumerate(data) if b == 0x42]
    s_positions = [j for j, b in enumerate(data) if b == 0x53]
    print(f"  B positions: {b_positions}")
    print(f"  S positions: {s_positions}")


def find_tick_record_structure(pcap_file):
    """寻找 tick 记录结构"""
    s2c_frames = get_all_s2c_frames(pcap_file)

    # 找同时包含价格和成交量的帧
    both_frames = []
    vol_only_frames = []
    px_only_frames = []

    vol_pattern = struct.pack('<H', 10386)  # 92 28
    px_pattern = struct.pack('<H', 1141)    # 75 04

    for frame in s2c_frames:
        data = frame['data']
        has_vol = vol_pattern in data
        has_px = px_pattern in data

        if has_vol and has_px:
            both_frames.append(frame)
        elif has_vol:
            vol_only_frames.append(frame)
        elif has_px:
            px_only_frames.append(frame)

    print(f"\nFrames with both vol and px: {len(both_frames)}")
    print(f"Frames with vol only: {len(vol_only_frames)}")
    print(f"Frames with px only: {len(px_only_frames)}")

    # 分析同时包含两者的帧
    for frame in both_frames[:3]:
        analyze_single_frame_structure(frame, "BOTH vol+px")

    # 分析只有成交量的帧
    for frame in vol_only_frames[:3]:
        analyze_single_frame_structure(frame, "VOL only")

    # 分析只有价格的帧
    for frame in px_only_frames[:3]:
        analyze_single_frame_structure(frame, "PX only")


def analyze_frame_types(pcap_file):
    """分析帧类型分布"""
    s2c_frames = get_all_s2c_frames(pcap_file)

    # 按帧大小分类
    size_counter = Counter()
    for frame in s2c_frames:
        size_counter[frame['len']] += 1

    print(f"\nFrame size distribution (top 20):")
    for size, count in size_counter.most_common(20):
        print(f"  {size}B: {count} frames")

    # 分析每种大小的帧内容
    for target_size in [1400, 1237, 296, 303, 56, 100]:
        frames = [f for f in s2c_frames if f['len'] == target_size]
        if not frames:
            continue

        print(f"\n{'='*70}")
        print(f"Frames of size {target_size}B: {len(frames)} frames")
        print(f"{'='*70}")

        # 分析第一个帧
        frame = frames[0]
        data = frame['data']

        # 检查 Magic
        has_magic = data[:4] == b'\xfd\xfd\xfd\xfd'
        print(f"  Has Magic: {has_magic}")

        # 检查标记
        markers = []
        for m in [b'itb3.0', b'tb3.0', b'tb1.0', b'ltb1.0', b'JiTu', b'cv3.0', b'frame']:
            if m in data:
                idx = data.find(m)
                markers.append(f"{m.decode()}@{idx}")
        print(f"  Markers: {markers}")

        # 搜索特征常量
        vol_pattern = struct.pack('<H', 10386)
        px_pattern = struct.pack('<H', 1141)
        has_vol = vol_pattern in data
        has_px = px_pattern in data
        print(f"  Has volume 10386: {has_vol}")
        print(f"  Has price 1141: {has_px}")

        # 搜索方向字节
        b_count = data.count(0x42)
        s_count = data.count(0x53)
        print(f"  B count: {b_count}, S count: {s_count}")

        # 打印前 128 字节
        print(f"  First 128 bytes:")
        for j in range(0, min(128, len(data)), 32):
            hex_str = data[j:j+32].hex()
            formatted = ' '.join(hex_str[k:k+2] for k in range(0, len(hex_str), 2))
            print(f"    {j:04d}: {formatted}")

        # 如果有方向字节, 打印其位置周围的上下文
        if b_count > 0 or s_count > 0:
            for target_byte, label in [(0x42, 'B'), (0x53, 'S')]:
                positions = [j for j, b in enumerate(data) if b == target_byte]
                if positions:
                    # 打印第一个位置周围的上下文
                    pos = positions[0]
                    start = max(0, pos - 16)
                    end = min(len(data), pos + 16)
                    context = data[start:end]
                    hex_str = ' '.join(f'{b:02x}' for b in context)
                    print(f"  First {label} at {pos}: ...{hex_str}...")


def find_constant_bytes(pcap_file):
    """寻找在所有帧中都相同的字节位置"""
    s2c_frames = get_all_s2c_frames(pcap_file)

    # 只分析大帧 (>100B)
    large_frames = [f for f in s2c_frames if f['len'] > 100]

    if not large_frames:
        return

    # 找最小长度
    min_len = min(f['len'] for f in large_frames)

    # 对每个位置, 检查所有帧的字节是否相同
    constant_positions = []
    for pos in range(min_len):
        bytes_at_pos = [f['data'][pos] for f in large_frames]
        if len(set(bytes_at_pos)) == 1:
            constant_positions.append(pos)

    print(f"\nConstant byte positions in large frames ({len(large_frames)} frames):")
    print(f"  Total constant positions: {len(constant_positions)}")

    # 打印恒定字节的位置和值
    if constant_positions:
        # 找出连续的恒定字节段
        segments = []
        start = constant_positions[0]
        prev = constant_positions[0]
        for pos in constant_positions[1:]:
            if pos == prev + 1:
                prev = pos
            else:
                segments.append((start, prev))
                start = pos
                prev = pos
        segments.append((start, prev))

        print(f"  Constant segments: {len(segments)}")
        for start, end in segments[:20]:
            val = large_frames[0]['data'][start]
            print(f"    [{start}-{end}] ({end-start+1}B): 0x{val:02x} ({val})")


def main(pcap_file):
    # 1. 分析方向字节所在的帧
    analyze_direction_frame(pcap_file)

    # 2. 寻找 tick 记录结构
    find_tick_record_structure(pcap_file)

    # 3. 分析帧类型
    analyze_frame_types(pcap_file)

    # 4. 寻找恒定字节
    find_constant_bytes(pcap_file)


if __name__ == "__main__":
    pcap_file = sys.argv[1] if len(sys.argv) > 1 else PCAP_FILE
    main(pcap_file)
