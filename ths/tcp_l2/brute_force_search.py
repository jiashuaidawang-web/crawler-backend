#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
暴力搜索 pcap 中的特征常量

搜索目标:
1. 成交量 10386 = 0x2892 (各种编码)
2. 价格 11.41 → 1141 = 0x0475 (×100), 11410 = 0x2C92 (×1000), 114100 = 0x1BDD4 (×10000)
3. 股票代码 000001 (ASCII 和 UTF-16LE)
4. 时间戳特征

输出: 每个命中的帧编号、偏移量、上下文
"""

import sys
import struct
from collections import Counter, defaultdict
from scapy.all import rdpcap, TCP, Raw, IP

PCAP_FILE = r"D:/stock/data/000001_1432.pcapng"

# 特征常量
VOLUME = 10386
PRICE = 11.41
PRICE_X100 = int(PRICE * 100)      # 1141
PRICE_X1000 = int(PRICE * 1000)    # 11410
PRICE_X10000 = int(PRICE * 10000)  # 114100

def build_search_patterns():
    """构建所有搜索模式"""
    patterns = {}

    # 成交量编码
    patterns['vol_u16_le'] = struct.pack('<H', VOLUME)        # 92 28
    patterns['vol_u16_be'] = struct.pack('>H', VOLUME)        # 28 92
    patterns['vol_u32_le'] = struct.pack('<I', VOLUME)        # 92 28 00 00
    patterns['vol_u32_be'] = struct.pack('>I', VOLUME)        # 00 00 28 92

    # 价格编码 (×100)
    patterns['px100_u16_le'] = struct.pack('<H', PRICE_X100)   # 75 04
    patterns['px100_u16_be'] = struct.pack('>H', PRICE_X100)   # 04 75
    patterns['px100_u32_le'] = struct.pack('<I', PRICE_X100)   # 75 04 00 00
    patterns['px100_u32_be'] = struct.pack('>I', PRICE_X100)   # 00 00 04 75

    # 价格编码 (×1000)
    patterns['px1000_u16_le'] = struct.pack('<H', PRICE_X1000)  # 92 2c
    patterns['px1000_u16_be'] = struct.pack('>H', PRICE_X1000)  # 2c 92
    patterns['px1000_u32_le'] = struct.pack('<I', PRICE_X1000)  # 92 2c 00 00
    patterns['px1000_u32_be'] = struct.pack('>I', PRICE_X1000)  # 00 00 2c 92

    # 价格编码 (×10000)
    patterns['px10000_u32_le'] = struct.pack('<I', PRICE_X10000)  # d4 bd 01 00
    patterns['px10000_u32_be'] = struct.pack('>I', PRICE_X10000)  # 00 01 bd d4

    # 股票代码
    patterns['code_ascii'] = b'000001'                           # 30 30 30 30 30 30
    patterns['code_utf16le'] = '000001'.encode('utf-16-le')     # 30 00 30 00 30 00 30 00 30 00 30 00

    # 方向字节
    patterns['dir_B'] = b'B'  # 42
    patterns['dir_S'] = b'S'  # 53

    return patterns


def search_all_frames(pcap_file):
    """在所有 S->C 帧中搜索特征常量"""
    packets = rdpcap(pcap_file)
    patterns = build_search_patterns()

    # 提取所有 S->C 帧
    s2c_frames = []
    for i, pkt in enumerate(packets):
        if pkt.haslayer(TCP) and pkt.haslayer(IP) and pkt.haslayer(Raw):
            tcp = pkt[TCP]
            ip = pkt[IP]
            if tcp.sport == 9528:
                raw = pkt[Raw].load
                s2c_frames.append({
                    'pkt_idx': i,
                    'data': raw,
                    'len': len(raw),
                    'time': float(pkt.time) if hasattr(pkt, 'time') else 0,
                })

    print(f"S->C frames: {len(s2c_frames)}")

    # 搜索
    all_hits = defaultdict(list)

    for frame in s2c_frames:
        data = frame['data']
        for pat_name, pat_bytes in patterns.items():
            pos = 0
            while True:
                idx = data.find(pat_bytes, pos)
                if idx == -1:
                    break
                all_hits[pat_name].append({
                    'pkt_idx': frame['pkt_idx'],
                    'offset': idx,
                    'frame_len': frame['len'],
                    'time': frame['time'],
                    'context': data[max(0,idx-8):idx+len(pat_bytes)+8].hex(),
                })
                pos = idx + 1

    # 输出结果
    print(f"\n{'='*70}")
    print("SEARCH RESULTS")
    print(f"{'='*70}")

    for pat_name, hits in sorted(all_hits.items(), key=lambda x: -len(x[1])):
        if not hits:
            continue

        print(f"\n--- {pat_name} ({patterns[pat_name].hex()}): {len(hits)} hits ---")

        # 按帧分组
        by_frame = defaultdict(list)
        for h in hits:
            by_frame[h['pkt_idx']].append(h)

        print(f"  Found in {len(by_frame)} frames")

        # 统计偏移量
        offset_counter = Counter(h['offset'] for h in hits)
        print(f"  Offset distribution (Top 10):")
        for offset, count in offset_counter.most_common(10):
            print(f"    offset={offset:4d} x{count:3d}")

        # 打印前 5 个命中的上下文
        print(f"  Sample contexts:")
        for h in hits[:5]:
            print(f"    pkt={h['pkt_idx']:5d} offset={h['offset']:4d} len={h['frame_len']:4d} ctx={h['context']}")

    return all_hits


def analyze_largest_frames(pcap_file):
    """分析最大的 S->C 帧的内容"""
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

    # 按大小排序
    s2c_frames.sort(key=lambda x: -x['len'])

    print(f"\n{'='*70}")
    print(f"LARGEST S->C FRAMES")
    print(f"{'='*70}")

    for i, frame in enumerate(s2c_frames[:10]):
        data = frame['data']
        print(f"\n--- Frame {i}: pkt={frame['pkt_idx']}, {frame['len']}B ---")

        # 检查是否包含 Magic
        has_magic = b'\xfd\xfd\xfd\xfd' in data
        print(f"  Has Magic: {has_magic}")

        # 检查包含的标记
        markers = []
        for m in [b'itb3.0', b'tb3.0', b'tb1.0', b'ltb1.0', b'JiTu', b'frame', b'stockcode']:
            if m in data:
                markers.append(m.decode())
        print(f"  Markers: {markers}")

        # 搜索特征常量
        found_patterns = []
        patterns = build_search_patterns()
        for pat_name, pat_bytes in patterns.items():
            if pat_bytes in data:
                # 找第一个出现位置
                idx = data.find(pat_bytes)
                found_patterns.append(f"{pat_name}@{idx}")
        print(f"  Found patterns: {found_patterns}")

        # 打印前 256 字节 hex
        print(f"  Hex dump (first 256B):")
        for j in range(0, min(256, len(data)), 32):
            hex_str = data[j:j+32].hex()
            formatted = ' '.join(hex_str[k:k+8] for k in range(0, len(hex_str), 8))
            ascii_str = ''.join(chr(b) if 32 <= b < 127 else '.' for b in data[j:j+32])
            print(f"    {j:04d}: {formatted}  {ascii_str}")


def find_tick_data_location(pcap_file):
    """定位逐笔成交数据在帧中的位置"""
    packets = rdpcap(pcap_file)

    # 找包含 "000001" 股票代码的帧
    s2c_frames = []
    for i, pkt in enumerate(packets):
        if pkt.haslayer(TCP) and pkt.haslayer(IP) and pkt.haslayer(Raw):
            tcp = pkt[TCP]
            if tcp.sport == 9528:
                raw = pkt[Raw].load
                if b'000001' in raw or b'000030' in raw:
                    s2c_frames.append({
                        'pkt_idx': i,
                        'data': raw,
                        'len': len(raw),
                        'time': float(pkt.time) if hasattr(pkt, 'time') else 0,
                    })

    print(f"\n{'='*70}")
    print(f"FRAMES CONTAINING STOCK CODE 000001")
    print(f"{'='*70}")
    print(f"Found: {len(s2c_frames)} frames")

    for i, frame in enumerate(s2c_frames[:5]):
        data = frame['data']
        print(f"\n--- Frame {i}: pkt={frame['pkt_idx']}, {frame['len']}B ---")

        # 找所有 "000001" 出现位置
        pos = 0
        occurrences = []
        while True:
            idx = data.find(b'000001', pos)
            if idx == -1:
                break
            occurrences.append(idx)
            pos = idx + 1

        print(f"  '000001' found at offsets: {occurrences}")

        # 对每个出现位置, 打印上下文
        for idx in occurrences[:3]:
            context = data[max(0,idx-16):idx+32]
            hex_str = context.hex()
            formatted = ' '.join(hex_str[k:k+2] for k in range(0, len(hex_str), 2))
            print(f"    @{idx:4d}: {formatted}")


if __name__ == "__main__":
    pcap_file = sys.argv[1] if len(sys.argv) > 1 else PCAP_FILE

    print(f"Analyzing: {pcap_file}")
    print(f"Target: 000001, Price={PRICE}, Volume={VOLUME}")

    # 1. 暴力搜索
    hits = search_all_frames(pcap_file)

    # 2. 分析最大帧
    analyze_largest_frames(pcap_file)

    # 3. 定位股票代码
    find_tick_data_location(pcap_file)
