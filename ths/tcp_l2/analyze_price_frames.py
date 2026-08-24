#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
分析包含价格的帧, 寻找逐笔成交记录结构

关键发现:
1. 价格 1142 在多个帧的 offset 175/181/184/186/187
2. 这些偏移量一致, 说明有固定的记录结构
3. 需要分析这些帧的完整结构
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
        if b'{' in data or b'"' in data:
            return 'json'
        return 'magic_unknown'
    return 'unknown'


def find_frames_with_price_at_offset(pcap_file, target_offsets):
    """找到在指定偏移量有价格的帧"""
    s2c_frames = get_all_s2c_frames(pcap_file)

    # 搜索价格 1141/1142/1143
    price_patterns = {
        1141: struct.pack('<H', 1141),  # 75 04
        1142: struct.pack('<H', 1142),  # 76 04
        1143: struct.pack('<H', 1143),  # 77 04
    }

    results = defaultdict(list)
    for frame in s2c_frames:
        # 排除已知帧类型
        frame_type = classify_frame(frame['data'])
        if frame_type in ['itb3.0', 'json']:
            continue

        data = frame['data']
        for px, pattern in price_patterns.items():
            pos = 0
            while True:
                idx = data.find(pattern, pos)
                if idx == -1:
                    break
                # 检查偏移量是否在目标范围内
                if idx in target_offsets:
                    results[px].append((frame['pkt_idx'], idx, frame['len']))
                pos = idx + 1

    return results


def analyze_frame_structure(pcap_file, pkt_idx, px_offset):
    """分析帧结构"""
    s2c_frames = get_all_s2c_frames(pcap_file)

    for frame in s2c_frames:
        if frame['pkt_idx'] == pkt_idx:
            data = frame['data']
            print(f"\n{'='*70}")
            print(f"Packet {pkt_idx} ({frame['len']}B) - Price at {px_offset}")
            print(f"{'='*70}")

            # 打印完整 hex dump
            for j in range(0, len(data), 32):
                hex_str = data[j:j+32].hex()
                formatted = ' '.join(hex_str[k:k+2] for k in range(0, len(hex_str), 2))
                ascii_str = ''.join(chr(b) if 32 <= b < 127 else '.' for b in data[j:j+32])
                print(f"  {j:04d}: {formatted}  {ascii_str}")

            # 分析价格周围的字段
            print(f"\n  Fields around price at {px_offset}:")
            for offset in range(-32, 32, 2):
                pos = px_offset + offset
                if 0 <= pos < len(data) - 1:
                    val_u16 = struct.unpack_from('<H', data, pos)[0]
                    val_u32 = None
                    if pos + 4 <= len(data):
                        val_u32 = struct.unpack_from('<I', data, pos)[0]

                    # 检查是否是方向字节
                    byte_val = data[pos]
                    dir_str = ""
                    if byte_val in [0x00, 0x01, 0x02, 0x03]:
                        dir_str = f" [DIR? 0x{byte_val:02x}]"

                    if val_u32 is not None:
                        print(f"    offset {offset:+3d}: u16={val_u16:5d} (0x{val_u16:04x}), u32={val_u32:10d} (0x{val_u32:08x}){dir_str}")
                    else:
                        print(f"    offset {offset:+3d}: u16={val_u16:5d} (0x{val_u16:04x}){dir_str}")

            # 搜索成交量
            print(f"\n  Volume search:")
            for vol in [2062, 1729, 775, 527, 526, 487, 457, 410, 393, 389, 372, 366, 342, 325, 318, 260, 249, 246, 231, 224, 207, 203, 196, 171, 156, 154, 150, 148, 141, 128, 125, 117, 115, 100, 91, 90, 85, 79, 75, 70, 68, 67, 63, 57, 40, 19, 7]:
                pattern = struct.pack('<H', vol)
                pos = 0
                while True:
                    idx = data.find(pattern, pos)
                    if idx == -1:
                        break
                    distance = idx - px_offset
                    print(f"    Volume {vol} at {idx}, distance from price: {distance}")
                    pos = idx + 1

            return

    print(f"Packet {pkt_idx} not found")


def find_tick_record_size(pcap_file):
    """寻找逐笔成交记录大小"""
    s2c_frames = get_all_s2c_frames(pcap_file)

    # 搜索价格 1142 的所有位置
    px_pattern = struct.pack('<H', 1142)  # 76 04
    px_hits = []

    for frame in s2c_frames:
        # 排除已知帧类型
        frame_type = classify_frame(frame['data'])
        if frame_type in ['itb3.0', 'json']:
            continue

        data = frame['data']
        pos = 0
        while True:
            idx = data.find(px_pattern, pos)
            if idx == -1:
                break
            px_hits.append((frame['pkt_idx'], idx))
            pos = idx + 1

    print(f"Price 1142 hits: {len(px_hits)}")

    # 按帧分组
    by_frame = defaultdict(list)
    for pkt_idx, offset in px_hits:
        by_frame[pkt_idx].append(offset)

    # 找有多个价格命中的帧
    multi_hits = {k: v for k, v in by_frame.items() if len(v) > 1}
    print(f"Frames with multiple price 1142 hits: {len(multi_hits)}")

    for pkt_idx, offsets in sorted(multi_hits.items())[:5]:
        print(f"\n  Packet {pkt_idx}: {offsets}")
        # 计算间隔
        intervals = [offsets[j+1] - offsets[j] for j in range(len(offsets)-1)]
        print(f"    Intervals: {intervals}")

        # 如果间隔固定, 说明是固定大小的记录
        if len(set(intervals)) == 1:
            record_size = intervals[0]
            print(f"    Fixed record size: {record_size} bytes")


def analyze_direction_encoding(pcap_file):
    """分析方向编码"""
    s2c_frames = get_all_s2c_frames(pcap_file)

    # 搜索价格 1142 的位置
    px_pattern = struct.pack('<H', 1142)

    # 收集价格周围的字节
    bytes_before = Counter()
    bytes_after = Counter()

    for frame in s2c_frames:
        # 排除已知帧类型
        frame_type = classify_frame(frame['data'])
        if frame_type in ['itb3.0', 'json']:
            continue

        data = frame['data']
        pos = 0
        while True:
            idx = data.find(px_pattern, pos)
            if idx == -1:
                break

            # 收集价格前后的字节
            for offset in range(-16, 16):
                check_pos = idx + offset
                if 0 <= check_pos < len(data):
                    byte_val = data[check_pos]
                    if offset < 0:
                        bytes_before[(offset, byte_val)] += 1
                    elif offset > 0:
                        bytes_after[(offset, byte_val)] += 1

            pos = idx + 1

    print("\nBytes before price (top 20):")
    for (offset, byte_val), count in sorted(bytes_before.items(), key=lambda x: -x[1])[:20]:
        print(f"    offset {offset:+3d}: 0x{byte_val:02x} ({byte_val:3d}) x{count}")

    print("\nBytes after price (top 20):")
    for (offset, byte_val), count in sorted(bytes_after.items(), key=lambda x: -x[1])[:20]:
        print(f"    offset {offset:+3d}: 0x{byte_val:02x} ({byte_val:3d}) x{count}")


def main(pcap_file):
    # 1. 寻找在目标偏移量有价格的帧
    target_offsets = [175, 181, 184, 186, 187, 226, 296, 451, 671, 872, 1168]
    results = find_frames_with_price_at_offset(pcap_file, target_offsets)

    print("Frames with price at target offsets:")
    for px, hits in sorted(results.items()):
        print(f"  Price {px}: {len(hits)} hits")
        for pkt_idx, offset, frame_len in hits[:5]:
            print(f"    pkt={pkt_idx}, offset={offset}, len={frame_len}")

    # 2. 分析特定帧的结构
    for px, hits in sorted(results.items()):
        if hits:
            pkt_idx, offset, _ = hits[0]
            analyze_frame_structure(pcap_file, pkt_idx, offset)
            break

    # 3. 寻找逐笔成交记录大小
    find_tick_record_size(pcap_file)

    # 4. 分析方向编码
    analyze_direction_encoding(pcap_file)


if __name__ == "__main__":
    pcap_file = sys.argv[1] if len(sys.argv) > 1 else PCAP_FILE
    main(pcap_file)
