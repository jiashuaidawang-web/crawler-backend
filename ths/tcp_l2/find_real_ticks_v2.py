#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
寻找真正的逐笔成交数据 v2

关键发现:
1. 方向字节 (B/S) 出现在 JSON 字符串中 (BONDMARKET, LimitBrokerDirectQs)
2. 真正的逐笔成交可能在 "magic_unknown" 帧中
3. 需要排除 JSON 和已知帧类型

策略:
1. 只分析 "magic_unknown" 帧 (有 Magic 但没有已知标记)
2. 在这些帧中搜索价格和成交量
3. 分析包含价格/成交量的帧的结构
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
        # 检查是否是 JSON
        if b'{' in data or b'"' in data:
            return 'json'
        return 'magic_unknown'
    return 'unknown'


def analyze_magic_unknown_frames(pcap_file):
    """分析 magic_unknown 帧"""
    s2c_frames = get_all_s2c_frames(pcap_file)

    # 只选择 magic_unknown 帧
    magic_frames = []
    for frame in s2c_frames:
        if classify_frame(frame['data']) == 'magic_unknown':
            magic_frames.append(frame)

    print(f"Magic unknown frames: {len(magic_frames)}")

    # 按大小分类
    size_counter = Counter(f['len'] for f in magic_frames)
    print(f"\nSize distribution:")
    for size, count in size_counter.most_common(10):
        print(f"  {size}B: {count} frames")

    # 搜索价格和成交量
    vol_pattern = struct.pack('<H', 10386)  # 92 28
    px_pattern = struct.pack('<H', 1141)    # 75 04

    vol_frames = []
    px_frames = []
    both_frames = []

    for frame in magic_frames:
        data = frame['data']
        has_vol = vol_pattern in data
        has_px = px_pattern in data

        if has_vol and has_px:
            both_frames.append(frame)
        elif has_vol:
            vol_frames.append(frame)
        elif has_px:
            px_frames.append(frame)

    print(f"\nFrames with volume 10386: {len(vol_frames)}")
    print(f"Frames with price 1141: {len(px_frames)}")
    print(f"Frames with both: {len(both_frames)}")

    # 分析包含价格的帧
    for frame in px_frames[:5]:
        analyze_frame_detail(frame, "PRICE")

    # 分析包含成交量的帧
    for frame in vol_frames[:5]:
        analyze_frame_detail(frame, "VOLUME")

    # 分析同时包含两者的帧
    for frame in both_frames[:5]:
        analyze_frame_detail(frame, "BOTH")


def analyze_frame_detail(frame, label):
    """详细分析帧"""
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

    # 对每个价格命中, 打印上下文
    for pp in px_positions:
        print(f"\n  --- Price at offset {pp} ---")
        start = max(0, pp - 32)
        end = min(len(data), pp + 32)
        context = data[start:end]
        for j in range(0, len(context), 32):
            abs_offset = start + j
            hex_str = context[j:j+32].hex()
            formatted = ' '.join(hex_str[k:k+2] for k in range(0, len(hex_str), 2))
            marker = " <<<PX" if abs_offset <= pp < abs_offset + 32 else ""
            print(f"    {abs_offset:04d}: {formatted}{marker}")

    # 对每个成交量命中, 打印上下文
    for vp in vol_positions:
        print(f"\n  --- Volume at offset {vp} ---")
        start = max(0, vp - 32)
        end = min(len(data), vp + 32)
        context = data[start:end]
        for j in range(0, len(context), 32):
            abs_offset = start + j
            hex_str = context[j:j+32].hex()
            formatted = ' '.join(hex_str[k:k+2] for k in range(0, len(hex_str), 2))
            marker = " <<<VOL" if abs_offset <= vp < abs_offset + 32 else ""
            print(f"    {abs_offset:04d}: {formatted}{marker}")


def search_all_price_encodings(pcap_file):
    """搜索所有可能的价格编码"""
    s2c_frames = get_all_s2c_frames(pcap_file)

    # 只选择 magic_unknown 帧
    magic_frames = [f for f in s2c_frames if classify_frame(f['data']) == 'magic_unknown']

    # 搜索 11.41 附近的所有可能编码
    price_targets = []
    for scale in [1, 10, 100, 1000, 10000]:
        val = int(11.41 * scale)
        if val < 65536:
            price_targets.append((f'px_x{scale}_le', struct.pack('<H', val)))
            price_targets.append((f'px_x{scale}_be', struct.pack('>H', val)))
        if val < 4294967296:
            price_targets.append((f'px_x{scale}_le32', struct.pack('<I', val)))
            price_targets.append((f'px_x{scale}_be32', struct.pack('>I', val)))

    # 在 magic_unknown 帧中搜索
    results = defaultdict(list)
    for frame in magic_frames:
        data = frame['data']
        for name, pattern in price_targets:
            pos = 0
            while True:
                idx = data.find(pattern, pos)
                if idx == -1:
                    break
                results[name].append((frame['pkt_idx'], idx))
                pos = idx + 1

    print("\nPrice search results in magic_unknown frames:")
    for name, hits in sorted(results.items(), key=lambda x: -len(x[1])):
        if hits:
            print(f"  {name}: {len(hits)} hits")
            for pkt_idx, offset in hits[:3]:
                print(f"    pkt={pkt_idx}, offset={offset}")


def search_all_vol_encodings(pcap_file):
    """搜索所有可能的成交量编码"""
    s2c_frames = get_all_s2c_frames(pcap_file)

    # 只选择 magic_unknown 帧
    magic_frames = [f for f in s2c_frames if classify_frame(f['data']) == 'magic_unknown']

    # 搜索 10386 附近的所有可能编码
    vol_targets = []
    for val in [10386, 103860, 1038600]:
        if val < 65536:
            vol_targets.append((f'vol_{val}_le', struct.pack('<H', val)))
            vol_targets.append((f'vol_{val}_be', struct.pack('>H', val)))
        if val < 4294967296:
            vol_targets.append((f'vol_{val}_le32', struct.pack('<I', val)))
            vol_targets.append((f'vol_{val}_be32', struct.pack('>I', val)))

    # 在 magic_unknown 帧中搜索
    results = defaultdict(list)
    for frame in magic_frames:
        data = frame['data']
        for name, pattern in vol_targets:
            pos = 0
            while True:
                idx = data.find(pattern, pos)
                if idx == -1:
                    break
                results[name].append((frame['pkt_idx'], idx))
                pos = idx + 1

    print("\nVolume search results in magic_unknown frames:")
    for name, hits in sorted(results.items(), key=lambda x: -len(x[1])):
        if hits:
            print(f"  {name}: {len(hits)} hits")
            for pkt_idx, offset in hits[:3]:
                print(f"    pkt={pkt_idx}, offset={offset}")


def analyze_tick_data_format(pcap_file):
    """分析逐笔成交数据格式"""
    s2c_frames = get_all_s2c_frames(pcap_file)

    # 只选择 magic_unknown 帧
    magic_frames = [f for f in s2c_frames if classify_frame(f['data']) == 'magic_unknown']

    # 找包含价格 1141 的帧
    px_pattern = struct.pack('<H', 1141)  # 75 04
    px_frames = [f for f in magic_frames if px_pattern in f['data']]

    print(f"\nMagic unknown frames with price 1141: {len(px_frames)}")

    if not px_frames:
        return

    # 分析这些帧的结构
    for frame in px_frames[:3]:
        data = frame['data']

        # 找价格位置
        px_pos = data.find(px_pattern)

        print(f"\n{'='*70}")
        print(f"Packet {frame['pkt_idx']} ({frame['len']}B) - Price at {px_pos}")
        print(f"{'='*70}")

        # 打印价格周围的上下文
        start = max(0, px_pos - 64)
        end = min(len(data), px_pos + 64)
        context = data[start:end]
        for j in range(0, len(context), 32):
            abs_offset = start + j
            hex_str = context[j:j+32].hex()
            formatted = ' '.join(hex_str[k:k+2] for k in range(0, len(hex_str), 2))
            marker = " <<<PX" if abs_offset <= px_pos < abs_offset + 32 else ""
            print(f"  {abs_offset:04d}: {formatted}{marker}")

        # 尝试解析字段
        # 假设字段布局: [time(4)] [price(2)] [dir(1)] [vol(2)] [padding]
        # 或其他布局

        # 尝试不同的字段偏移
        print(f"\n  Field candidates:")
        for offset in range(-20, 20, 2):
            pos = px_pos + offset
            if 0 <= pos < len(data) - 1:
                val_u16 = struct.unpack_from('<H', data, pos)[0]
                val_u32 = struct.unpack_from('<I', data, pos)[0] if pos + 4 <= len(data) else None
                print(f"    offset {offset:+3d}: u16={val_u16:5d} (0x{val_u16:04x}), u32={val_u32} (0x{val_u32:08x})" if val_u32 else f"    offset {offset:+3d}: u16={val_u16:5d} (0x{val_u16:04x})")


def main(pcap_file):
    # 1. 分析 magic_unknown 帧
    analyze_magic_unknown_frames(pcap_file)

    # 2. 搜索所有价格编码
    search_all_price_encodings(pcap_file)

    # 3. 搜索所有成交量编码
    search_all_vol_encodings(pcap_file)

    # 4. 分析逐笔成交数据格式
    analyze_tick_data_format(pcap_file)


if __name__ == "__main__":
    pcap_file = sys.argv[1] if len(sys.argv) > 1 else PCAP_FILE
    main(pcap_file)
