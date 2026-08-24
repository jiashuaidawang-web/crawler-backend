#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
基于真实 L2 数据的 ground truth 搜索

真实数据 (14:53-14:56):
时间     价格      操作 手
14:53    11.43    买 260
14:53    11.42    卖 100
14:53    11.42    卖 156
14:53    11.42    卖 70
14:53    11.43    买 19
14:53    11.43    买 249
14:53    11.43    买 68
14:53    11.42    平 7
14:53    11.42    卖 231
14:53    11.42    卖 318
14:53    11.42    卖 117
14:53    11.42    卖 128
14:53    11.42    卖 90
14:53    11.42    卖 141
14:53    11.42    卖 63
14:53    11.42    卖 141
14:53    11.42    平 149
14:53    11.41    卖 246
14:53    11.42    买 2062
14:54    11.42    卖 1729
14:54    11.42    平 420
14:54    11.41    卖 171
14:54    11.42    平 117
14:54    11.43    买 224
14:54    11.43    买 263
14:54    11.42    卖 325
14:54    11.41    卖 527
14:54    11.42    平 79
14:54    11.42    卖 207
14:54    11.43    买 393
14:54    11.42    平 67
14:54    11.43    买 90
14:54    11.41    卖 203
14:54    11.42    平 372
14:54    11.43    买 85
14:54    11.42    卖 526
14:54    11.42    卖 148
14:54    11.43    买 75
14:55    11.42    卖 91
14:56    11.42    卖 487
14:56    11.42    卖 260
14:56    11.42    卖 227
14:56    11.42    卖 457
14:56    11.42    买 342
14:56    11.42    卖 125
14:56    11.43    买 366
14:56    11.43    买 196
14:56    11.42    卖 775
14:56    11.42    卖 209
14:56    11.43    买 40
14:56    11.42    卖 163
14:56    11.43    买 154
14:56    11.42    卖 115
14:56    11.42    卖 389
14:56    11.41    卖 410
14:56    11.41    卖 57
14:56    11.41    卖 150

策略:
1. 搜索大单 (2062, 1729, 775, 527, 526, 487, 457, 410, 393, 389, 372, 366, 342, 325)
2. 搜索价格 (1141, 1142, 1143)
3. 分析字段布局
4. 确定方向编码
"""

import sys
import struct
from collections import Counter, defaultdict
from scapy.all import rdpcap, TCP, Raw, IP

PCAP_FILE = r"D:/stock/data/000001_1432.pcapng"

# Ground truth 数据
GROUND_TRUTH = [
    # (时间, 价格×100, 方向, 成交量)
    ("14:53", 1143, "B", 260),
    ("14:53", 1142, "S", 100),
    ("14:53", 1142, "S", 156),
    ("14:53", 1142, "S", 70),
    ("14:53", 1143, "B", 19),
    ("14:53", 1143, "B", 249),
    ("14:53", 1143, "B", 68),
    ("14:53", 1142, "F", 7),   # 平
    ("14:53", 1142, "S", 231),
    ("14:53", 1142, "S", 318),
    ("14:53", 1142, "S", 117),
    ("14:53", 1142, "S", 128),
    ("14:53", 1142, "S", 90),
    ("14:53", 1142, "S", 141),
    ("14:53", 1142, "S", 63),
    ("14:53", 1142, "S", 141),
    ("14:53", 1142, "F", 149),  # 平
    ("14:53", 1141, "S", 246),
    ("14:53", 1142, "B", 2062),  # 大单!
    ("14:54", 1142, "S", 1729),  # 大单!
    ("14:54", 1142, "F", 420),   # 平
    ("14:54", 1141, "S", 171),
    ("14:54", 1142, "F", 117),   # 平
    ("14:54", 1143, "B", 224),
    ("14:54", 1143, "B", 263),
    ("14:54", 1142, "S", 325),
    ("14:54", 1141, "S", 527),
    ("14:54", 1142, "F", 79),    # 平
    ("14:54", 1142, "S", 207),
    ("14:54", 1143, "B", 393),
    ("14:54", 1142, "F", 67),    # 平
    ("14:54", 1143, "B", 90),
    ("14:54", 1141, "S", 203),
    ("14:54", 1142, "F", 372),   # 平
    ("14:54", 1143, "B", 85),
    ("14:54", 1142, "S", 526),
    ("14:54", 1142, "S", 148),
    ("14:54", 1143, "B", 75),
    ("14:55", 1142, "S", 91),
    ("14:56", 1142, "S", 487),
    ("14:56", 1142, "S", 260),
    ("14:56", 1142, "S", 227),
    ("14:56", 1142, "S", 457),
    ("14:56", 1142, "B", 342),
    ("14:56", 1142, "S", 125),
    ("14:56", 1143, "B", 366),
    ("14:56", 1143, "B", 196),
    ("14:56", 1142, "S", 775),
    ("14:56", 1142, "S", 209),
    ("14:56", 1143, "B", 40),
    ("14:56", 1142, "S", 163),
    ("14:56", 1143, "B", 154),
    ("14:56", 1142, "S", 115),
    ("14:56", 1142, "S", 389),
    ("14:56", 1141, "S", 410),
    ("14:56", 1141, "S", 57),
    ("14:56", 1141, "S", 150),
]

# 大单锚点 (用于精确定位)
LARGE_VOLUMES = [2062, 1729, 775, 527, 526, 487, 457, 410, 393, 389, 372, 366, 342, 325]

# 价格
PRICES = [1141, 1142, 1143]


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


def search_large_volumes(pcap_file):
    """搜索大单成交量"""
    s2c_frames = get_all_s2c_frames(pcap_file)

    print(f"Total S->C frames: {len(s2c_frames)}")

    # 搜索每个大单
    results = defaultdict(list)
    for frame in s2c_frames:
        data = frame['data']
        for vol in LARGE_VOLUMES:
            # u16_le
            pattern = struct.pack('<H', vol)
            pos = 0
            while True:
                idx = data.find(pattern, pos)
                if idx == -1:
                    break
                results[vol].append((frame['pkt_idx'], idx, 'u16_le'))
                pos = idx + 1

            # u16_be
            pattern = struct.pack('>H', vol)
            pos = 0
            while True:
                idx = data.find(pattern, pos)
                if idx == -1:
                    break
                results[vol].append((frame['pkt_idx'], idx, 'u16_be'))
                pos = idx + 1

    print(f"\nLarge volume search results:")
    for vol in sorted(results.keys(), reverse=True):
        hits = results[vol]
        print(f"  Volume {vol} (0x{vol:04x}): {len(hits)} hits")
        for pkt_idx, offset, encoding in hits[:5]:
            print(f"    pkt={pkt_idx}, offset={offset}, encoding={encoding}")

    return results


def search_prices(pcap_file):
    """搜索价格"""
    s2c_frames = get_all_s2c_frames(pcap_file)

    results = defaultdict(list)
    for frame in s2c_frames:
        data = frame['data']
        for px in PRICES:
            # u16_le
            pattern = struct.pack('<H', px)
            pos = 0
            while True:
                idx = data.find(pattern, pos)
                if idx == -1:
                    break
                results[px].append((frame['pkt_idx'], idx, 'u16_le'))
                pos = idx + 1

            # u16_be
            pattern = struct.pack('>H', px)
            pos = 0
            while True:
                idx = data.find(pattern, pos)
                if idx == -1:
                    break
                results[px].append((frame['pkt_idx'], idx, 'u16_be'))
                pos = idx + 1

    print(f"\nPrice search results:")
    for px in sorted(results.keys()):
        hits = results[px]
        print(f"  Price {px} (0x{px:04x}): {len(hits)} hits")
        for pkt_idx, offset, encoding in hits[:10]:
            print(f"    pkt={pkt_idx}, offset={offset}, encoding={encoding}")

    return results


def analyze_frame_with_both(pcap_file):
    """分析同时包含价格和成交量的帧"""
    s2c_frames = get_all_s2c_frames(pcap_file)

    # 找同时包含价格 (1141/1142/1143) 和大单成交量的帧
    both_frames = []
    for frame in s2c_frames:
        data = frame['data']

        # 搜索价格
        px_hits = []
        for px in PRICES:
            pattern = struct.pack('<H', px)
            pos = 0
            while True:
                idx = data.find(pattern, pos)
                if idx == -1:
                    break
                px_hits.append((idx, px))
                pos = idx + 1

        # 搜索大单成交量
        vol_hits = []
        for vol in LARGE_VOLUMES:
            pattern = struct.pack('<H', vol)
            pos = 0
            while True:
                idx = data.find(pattern, pos)
                if idx == -1:
                    break
                vol_hits.append((idx, vol))
                pos = idx + 1

        if px_hits and vol_hits:
            both_frames.append({
                **frame,
                'px_hits': px_hits,
                'vol_hits': vol_hits,
            })

    print(f"\nFrames with both price and large volume: {len(both_frames)}")

    for frame in both_frames[:10]:
        print(f"\n{'='*70}")
        print(f"Packet {frame['pkt_idx']} ({frame['len']}B)")
        print(f"Price hits: {frame['px_hits']}")
        print(f"Volume hits: {frame['vol_hits']}")
        print(f"{'='*70}")

        # 打印完整 hex dump
        data = frame['data']
        for j in range(0, len(data), 32):
            hex_str = data[j:j+32].hex()
            formatted = ' '.join(hex_str[k:k+2] for k in range(0, len(hex_str), 2))
            ascii_str = ''.join(chr(b) if 32 <= b < 127 else '.' for b in data[j:j+32])
            print(f"  {j:04d}: {formatted}  {ascii_str}")

        # 分析价格和成交量的相对位置
        for px_idx, px_val in frame['px_hits']:
            for vol_idx, vol_val in frame['vol_hits']:
                distance = vol_idx - px_idx
                print(f"\n  Price {px_val} at {px_idx}, Volume {vol_val} at {vol_idx}, distance={distance}")

                # 打印两者之间的字节
                start = min(px_idx, vol_idx)
                end = max(px_idx, vol_idx) + 2
                context = data[start:end]
                hex_str = ' '.join(f'{b:02x}' for b in context)
                print(f"    Between: {hex_str}")

                # 分析方向字节
                # 方向字节应该在价格或成交量附近
                for check_pos in [px_idx - 4, px_idx - 2, px_idx + 2, px_idx + 4,
                                  vol_idx - 4, vol_idx - 2, vol_idx + 2, vol_idx + 4]:
                    if 0 <= check_pos < len(data):
                        byte_val = data[check_pos]
                        if byte_val in [0x00, 0x01, 0x02, 0x03, 0x42, 0x53, 0x4E, 0x46]:
                            print(f"    Direction candidate at {check_pos}: 0x{byte_val:02x} ({chr(byte_val) if 32 <= byte_val < 127 else '.'})")


def find_tick_record_layout(pcap_file):
    """寻找 tick 记录布局"""
    s2c_frames = get_all_s2c_frames(pcap_file)

    # 搜索所有价格和成交量的组合
    all_px_hits = []
    all_vol_hits = []

    for frame in s2c_frames:
        data = frame['data']
        for px in PRICES:
            pattern = struct.pack('<H', px)
            pos = 0
            while True:
                idx = data.find(pattern, pos)
                if idx == -1:
                    break
                all_px_hits.append((frame['pkt_idx'], idx, px))
                pos = idx + 1

        for vol in LARGE_VOLUMES:
            pattern = struct.pack('<H', vol)
            pos = 0
            while True:
                idx = data.find(pattern, pos)
                if idx == -1:
                    break
                all_vol_hits.append((frame['pkt_idx'], idx, vol))
                pos = idx + 1

    print(f"\nAll price hits: {len(all_px_hits)}")
    print(f"All volume hits: {len(all_vol_hits)}")

    # 按帧分组
    px_by_frame = defaultdict(list)
    vol_by_frame = defaultdict(list)

    for pkt_idx, offset, px in all_px_hits:
        px_by_frame[pkt_idx].append((offset, px))

    for pkt_idx, offset, vol in all_vol_hits:
        vol_by_frame[pkt_idx].append((offset, vol))

    # 找同时包含价格和成交量的帧
    common_frames = set(px_by_frame.keys()) & set(vol_by_frame.keys())
    print(f"Frames with both price and volume: {len(common_frames)}")

    # 分析这些帧
    for pkt_idx in sorted(common_frames)[:10]:
        px_hits = px_by_frame[pkt_idx]
        vol_hits = vol_by_frame[pkt_idx]

        print(f"\n  Packet {pkt_idx}:")
        print(f"    Prices: {px_hits}")
        print(f"    Volumes: {vol_hits}")

        # 计算价格和成交量的距离
        for px_offset, px_val in px_hits:
            for vol_offset, vol_val in vol_hits:
                distance = vol_offset - px_offset
                print(f"    Price {px_val}@{px_offset} -> Volume {vol_val}@{vol_offset}: distance={distance}")


def main(pcap_file):
    # 1. 搜索大单成交量
    search_large_volumes(pcap_file)

    # 2. 搜索价格
    search_prices(pcap_file)

    # 3. 分析同时包含两者的帧
    analyze_frame_with_both(pcap_file)

    # 4. 寻找 tick 记录布局
    find_tick_record_layout(pcap_file)


if __name__ == "__main__":
    pcap_file = sys.argv[1] if len(sys.argv) > 1 else PCAP_FILE
    main(pcap_file)
