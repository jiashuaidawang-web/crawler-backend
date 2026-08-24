#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
分析包含特征常量的帧, 找出完整的 tick 字段布局

重点分析:
1. pkt=1864 (vol@831, dir_B@51, dir_S@97)
2. pkt=2136 (vol@966, vol@969)
3. pkt=4803 (vol@190)
4. pkt=2022 (px@222, dir_B@148, dir_S@378)
5. pkt=2077 (px@1168)
6. pkt=103254 (px@184)
7. pkt=123078 (px@182)
"""

import sys
import struct
from scapy.all import rdpcap, TCP, Raw, IP

PCAP_FILE = r"D:/stock/data/000001_1432.pcapng"

# 目标帧
TARGET_PACKETS = [1864, 2136, 4803, 2022, 2077, 103254, 123078]

def get_packet(pcap_file, target_idx):
    """获取指定索引的包"""
    packets = rdpcap(pcap_file)
    for i, pkt in enumerate(packets):
        if i == target_idx:
            if pkt.haslayer(TCP) and pkt.haslayer(IP) and pkt.haslayer(Raw):
                tcp = pkt[TCP]
                ip = pkt[IP]
                if tcp.sport == 9528:
                    return {
                        'pkt_idx': i,
                        'data': pkt[Raw].load,
                        'len': len(pkt[Raw].load),
                        'time': float(pkt.time) if hasattr(pkt, 'time') else 0,
                    }
    return None


def full_hex_dump(data, label=""):
    """完整 hex dump"""
    print(f"\n{'='*70}")
    print(f"{label} ({len(data)} bytes)")
    print(f"{'='*70}")
    for j in range(0, len(data), 32):
        hex_str = data[j:j+32].hex()
        formatted = ' '.join(hex_str[k:k+8] for k in range(0, len(hex_str), 8))
        ascii_str = ''.join(chr(b) if 32 <= b < 127 else '.' for b in data[j:j+32])
        print(f"  {j:04d}: {formatted}  {ascii_str}")


def analyze_packet(pcap_file, pkt_idx):
    """分析单个包"""
    frame = get_packet(pcap_file, pkt_idx)
    if not frame:
        print(f"Packet {pkt_idx}: not found or not S->C")
        return

    data = frame['data']
    print(f"\n{'#'*70}")
    print(f"# Packet {pkt_idx} ({frame['len']}B)")
    print(f"{'#'*70}")

    # 完整 hex dump
    full_hex_dump(data, f"Full packet {pkt_idx}")

    # 搜索特征常量
    vol_pattern = struct.pack('<H', 10386)  # 92 28
    px_pattern = struct.pack('<H', 1141)    # 75 04

    print(f"\n  Volume pattern (92 28) positions: ", end="")
    pos = 0
    vol_positions = []
    while True:
        idx = data.find(vol_pattern, pos)
        if idx == -1:
            break
        vol_positions.append(idx)
        pos = idx + 1
    print(vol_positions)

    print(f"  Price pattern (75 04) positions: ", end="")
    pos = 0
    px_positions = []
    while True:
        idx = data.find(px_pattern, pos)
        if idx == -1:
            break
        px_positions.append(idx)
        pos = idx + 1
    print(px_positions)

    # 对每个 volume 命中, 打印上下文
    for vp in vol_positions:
        print(f"\n  --- Volume at offset {vp} ---")
        # 打印前后 64 字节
        start = max(0, vp - 64)
        end = min(len(data), vp + 64)
        context = data[start:end]
        for j in range(0, len(context), 32):
            abs_offset = start + j
            hex_str = context[j:j+32].hex()
            formatted = ' '.join(hex_str[k:k+2] for k in range(0, len(hex_str), 2))
            marker = " <<<VOL" if abs_offset <= vp < abs_offset + 32 else ""
            print(f"    {abs_offset:04d}: {formatted}{marker}")

    # 对每个 price 命中, 打印上下文
    for pp in px_positions:
        print(f"\n  --- Price at offset {pp} ---")
        start = max(0, pp - 64)
        end = min(len(data), pp + 64)
        context = data[start:end]
        for j in range(0, len(context), 32):
            abs_offset = start + j
            hex_str = context[j:j+32].hex()
            formatted = ' '.join(hex_str[k:k+2] for k in range(0, len(hex_str), 2))
            marker = " <<<PX" if abs_offset <= pp < abs_offset + 32 else ""
            print(f"    {abs_offset:04d}: {formatted}{marker}")


def find_direction_pattern(pcap_file):
    """分析方向字节的分布模式"""
    packets = rdpcap(pcap_file)

    # 收集所有 S->C 帧
    s2c_frames = []
    for i, pkt in enumerate(packets):
        if pkt.haslayer(TCP) and pkt.haslayer(IP) and pkt.haslayer(Raw):
            tcp = pkt[TCP]
            if tcp.sport == 9528:
                raw = pkt[Raw].load
                s2c_frames.append((i, raw))

    # 搜索 'B' 和 'S' 的聚集模式
    print(f"\n{'='*70}")
    print("Direction byte pattern analysis")
    print(f"{'='*70}")

    # 找 'B' 和 'S' 同时出现的帧
    both_frames = []
    for pkt_idx, data in s2c_frames:
        b_positions = []
        s_positions = []
        pos = 0
        while True:
            idx = data.find(b'B', pos)
            if idx == -1:
                break
            b_positions.append(idx)
            pos = idx + 1
        pos = 0
        while True:
            idx = data.find(b'S', pos)
            if idx == -1:
                break
            s_positions.append(idx)
            pos = idx + 1

        if len(b_positions) > 5 and len(s_positions) > 5:
            both_frames.append((pkt_idx, len(data), b_positions, s_positions))

    print(f"Frames with both B and S (>5 each): {len(both_frames)}")

    # 分析前 5 个这样的帧
    for pkt_idx, data_len, b_pos, s_pos in both_frames[:5]:
        print(f"\n  Packet {pkt_idx} ({data_len}B): B={len(b_pos)}, S={len(s_pos)}")
        print(f"    B positions (first 20): {b_pos[:20]}")
        print(f"    S positions (first 20): {s_pos[:20]}")

        # 计算 B 和 S 位置的差值
        if b_pos and s_pos:
            # 找最近的 B-S 对
            min_dist = float('inf')
            best_pair = None
            for b in b_pos[:10]:
                for s in s_pos[:10]:
                    dist = abs(b - s)
                    if dist < min_dist:
                        min_dist = dist
                        best_pair = (b, s)
            if best_pair:
                print(f"    Closest B-S pair: B@{best_pair[0]}, S@{best_pair[1]}, dist={min_dist}")


def main(pcap_file):
    # 分析目标帧
    for pkt_idx in TARGET_PACKETS:
        analyze_packet(pcap_file, pkt_idx)

    # 分析方向模式
    find_direction_pattern(pcap_file)


if __name__ == "__main__":
    pcap_file = sys.argv[1] if len(sys.argv) > 1 else PCAP_FILE
    main(pcap_file)
