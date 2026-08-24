#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
分析方向字节在固定偏移量的帧, 找出完整的 tick 字段布局

关键发现:
- 'B' (0x42) 在 offset 978 出现 30 次
- 'S' (0x53) 在 offset 224/247 各出现 30 次

假设: tick 数据有固定结构, 方向字节在固定偏移量
"""

import sys
import struct
from collections import Counter, defaultdict
from scapy.all import rdpcap, TCP, Raw, IP

PCAP_FILE = r"D:/stock/data/000001_1432.pcapng"


def find_frames_with_direction_at_offset(pcap_file, offset, target_byte):
    """找到在指定偏移量有指定字节的所有帧"""
    packets = rdpcap(pcap_file)

    matching_frames = []
    for i, pkt in enumerate(packets):
        if pkt.haslayer(TCP) and pkt.haslayer(IP) and pkt.haslayer(Raw):
            tcp = pkt[TCP]
            if tcp.sport == 9528:
                raw = pkt[Raw].load
                if len(raw) > offset and raw[offset] == target_byte:
                    matching_frames.append({
                        'pkt_idx': i,
                        'data': raw,
                        'len': len(raw),
                        'time': float(pkt.time) if hasattr(pkt, 'time') else 0,
                    })

    return matching_frames


def analyze_field_layout(frames, dir_offset, label):
    """分析字段布局"""
    print(f"\n{'='*70}")
    print(f"Direction '{chr(frames[0]['data'][dir_offset])}' at offset {dir_offset} ({label})")
    print(f"Found in {len(frames)} frames")
    print(f"{'='*70}")

    if not frames:
        return

    # 对每个帧, 提取方向字节前后的数据
    # 尝试不同的字段布局假设

    # 假设1: 方向字节前是价格, 后是成交量
    # 假设2: 方向字节前是成交量, 后是价格
    # 假设3: 方向字节前后都是其他字段

    # 收集方向字节前后的值
    before_dir_u16 = []
    after_dir_u16 = []
    before_dir_u32 = []
    after_dir_u32 = []

    for frame in frames:
        data = frame['data']
        # 方向字节前 2 字节 (u16_le)
        if dir_offset >= 2:
            val = struct.unpack_from('<H', data, dir_offset - 2)[0]
            before_dir_u16.append(val)
        # 方向字节后 2 字节 (u16_le)
        if dir_offset + 3 < len(data):
            val = struct.unpack_from('<H', data, dir_offset + 1)[0]
            after_dir_u16.append(val)
        # 方向字节前 4 字节 (u32_le)
        if dir_offset >= 4:
            val = struct.unpack_from('<I', data, dir_offset - 4)[0]
            before_dir_u32.append(val)
        # 方向字节后 4 字节 (u32_le)
        if dir_offset + 5 < len(data):
            val = struct.unpack_from('<I', data, dir_offset + 1)[0]
            after_dir_u32.append(val)

    print(f"\n  Before dir (u16_le, {len(before_dir_u16)} values):")
    print(f"    Range: {min(before_dir_u16)} - {max(before_dir_u16)}")
    print(f"    Mean: {sum(before_dir_u16)//len(before_dir_u16)}")
    counter = Counter(before_dir_u16)
    print(f"    Top 10: {counter.most_common(10)}")

    print(f"\n  After dir (u16_le, {len(after_dir_u16)} values):")
    print(f"    Range: {min(after_dir_u16)} - {max(after_dir_u16)}")
    print(f"    Mean: {sum(after_dir_u16)//len(after_dir_u16)}")
    counter = Counter(after_dir_u16)
    print(f"    Top 10: {counter.most_common(10)}")

    print(f"\n  Before dir (u32_le, {len(before_dir_u32)} values):")
    print(f"    Range: {min(before_dir_u32)} - {max(before_dir_u32)}")
    print(f"    Mean: {sum(before_dir_u32)//len(before_dir_u32)}")
    counter = Counter(before_dir_u32)
    print(f"    Top 10: {counter.most_common(10)}")

    print(f"\n  After dir (u32_le, {len(after_dir_u32)} values):")
    print(f"    Range: {min(after_dir_u32)} - {max(after_dir_u32)}")
    print(f"    Mean: {sum(after_dir_u32)//len(after_dir_u32)}")
    counter = Counter(after_dir_u32)
    print(f"    Top 10: {counter.most_common(10)}")


def analyze_tick_record_structure(frames, dir_offset, label):
    """分析 tick 记录结构"""
    print(f"\n{'='*70}")
    print(f"Tick record structure analysis for dir at offset {dir_offset} ({label})")
    print(f"{'='*70}")

    if not frames:
        return

    # 假设 tick 记录是固定大小的, 方向字节在记录内的固定偏移
    # 尝试不同的记录大小

    for record_size in [8, 12, 16, 20, 24, 28, 32, 36, 40, 48, 56, 64, 100]:
        # 计算记录起始偏移
        record_start = dir_offset % record_size
        dir_in_record = dir_offset - record_start

        # 检查所有帧是否都有相同方向的字节在相同位置
        consistent = True
        for frame in frames:
            data = frame['data']
            # 检查同一记录中相同偏移量
            pos = record_start + dir_in_record
            if pos >= len(data):
                consistent = False
                break
            if data[pos] != frames[0]['data'][dir_offset]:
                consistent = False
                break

        if consistent:
            print(f"\n  Record size {record_size}: dir at offset {dir_in_record} in record")
            print(f"    Record starts at offset {record_start}")

            # 提取记录中的其他字段
            # 假设字段布局: [time(4)] [price(2/4)] [dir(1)] [vol(2/4)] [padding]
            # 或其他布局

            # 打印前 5 个帧的记录内容
            for i, frame in enumerate(frames[:5]):
                data = frame['data']
                record = data[record_start:record_start + record_size]
                hex_str = ' '.join(f'{b:02x}' for b in record)
                print(f"    Frame {frame['pkt_idx']}: {hex_str}")


def find_all_direction_positions(pcap_file):
    """找到所有帧中方向字节的位置"""
    packets = rdpcap(pcap_file)

    # 收集所有 S->C 帧
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
                })

    # 找方向字节聚集的偏移量
    dir_positions = defaultdict(int)
    for frame in s2c_frames:
        data = frame['data']
        for j, b in enumerate(data):
            if b == 0x42 or b == 0x53:  # 'B' or 'S'
                dir_positions[j] += 1

    # 找出现次数最多的偏移量
    top_positions = Counter(dir_positions).most_common(20)
    print(f"\nTop direction byte positions:")
    for pos, count in top_positions:
        print(f"  offset {pos}: {count} times")

    return top_positions


def analyze_frame_with_both_directions(pcap_file):
    """分析同时包含 B 和 S 的帧"""
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
                })

    # 找同时包含多个 B 和 S 的帧
    multi_dir_frames = []
    for frame in s2c_frames:
        data = frame['data']
        b_count = data.count(0x42)
        s_count = data.count(0x53)
        if b_count >= 5 and s_count >= 5:
            multi_dir_frames.append({
                'pkt_idx': frame['pkt_idx'],
                'data': data,
                'len': frame['len'],
                'b_count': b_count,
                's_count': s_count,
            })

    print(f"\nFrames with multiple B and S: {len(multi_dir_frames)}")

    # 分析前 10 个帧
    for frame in multi_dir_frames[:10]:
        data = frame['data']
        print(f"\n  Packet {frame['pkt_idx']} ({frame['len']}B): B={frame['b_count']}, S={frame['s_count']}")

        # 找所有 B 和 S 的位置
        b_positions = [j for j, b in enumerate(data) if b == 0x42]
        s_positions = [j for j, b in enumerate(data) if b == 0x53]

        # 计算位置间隔
        if len(b_positions) > 1:
            b_intervals = [b_positions[j+1] - b_positions[j] for j in range(len(b_positions)-1)]
            print(f"    B intervals: {Counter(b_intervals).most_common(5)}")
        if len(s_positions) > 1:
            s_intervals = [s_positions[j+1] - s_positions[j] for j in range(len(s_positions)-1)]
            print(f"    S intervals: {Counter(s_intervals).most_common(5)}")

        # 找 B 和 S 的固定偏移量
        # 如果 tick 记录是固定大小的, 那么 B 和 S 应该在记录内的固定偏移
        # 尝试不同的记录大小
        for record_size in [8, 12, 16, 20, 24, 28, 32]:
            b_offsets = [pos % record_size for pos in b_positions]
            s_offsets = [pos % record_size for pos in s_positions]
            b_off_counter = Counter(b_offsets)
            s_off_counter = Counter(s_offsets)
            if b_off_counter and s_off_counter:
                b_most = b_off_counter.most_common(1)[0]
                s_most = s_off_counter.most_common(1)[0]
                if b_most[1] >= 3 and s_most[1] >= 3:
                    print(f"    Record size {record_size}: B at offset {b_most[0]} ({b_most[1]}x), S at offset {s_most[0]} ({s_most[1]}x)")


def main(pcap_file):
    # 1. 找到所有方向字节的位置
    top_positions = find_all_direction_positions(pcap_file)

    # 2. 分析方向字节在固定偏移量的帧
    for offset in [978, 224, 247]:
        for target_byte, label in [(0x42, 'B'), (0x53, 'S')]:
            frames = find_frames_with_direction_at_offset(pcap_file, offset, target_byte)
            if frames:
                analyze_field_layout(frames, offset, label)
                analyze_tick_record_structure(frames, offset, label)

    # 3. 分析同时包含 B 和 S 的帧
    analyze_frame_with_both_directions(pcap_file)


if __name__ == "__main__":
    pcap_file = sys.argv[1] if len(sys.argv) > 1 else PCAP_FILE
    main(pcap_file)
