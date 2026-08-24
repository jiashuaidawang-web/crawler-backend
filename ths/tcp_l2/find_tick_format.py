#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
从 pcap 中定位逐笔成交 (tb3.0) 的字段格式

策略:
1. 提取所有 tb3.0 帧 (56个)
2. 搜索价格常量 11.41 (编码为 1141/11410/114100)
3. 搜索成交量常量 10386 (编码为 0x2892)
4. 分析字段布局
"""

import sys
import struct
from collections import Counter, defaultdict
from scapy.all import rdpcap, TCP, Raw, IP

PCAP_FILE = r"D:/stock/data/000001_1432.pcapng"
STOCK_CODE = "000001"
CLOSE_PRICE = 11.41
CLOSE_VOLUME = 10386

def extract_frames(pcap_file):
    """提取所有 S->C 帧并按类型分组"""
    packets = rdpcap(pcap_file)

    frames = []
    for pkt in packets:
        if pkt.haslayer(TCP) and pkt.haslayer(IP) and pkt.haslayer(Raw):
            tcp = pkt[TCP]
            ip = pkt[IP]
            if tcp.sport == 9528:
                raw = pkt[Raw].load
                # 找所有 Magic 位置
                pos = 0
                while True:
                    idx = raw.find(b'\xfd\xfd\xfd\xfd', pos)
                    if idx == -1:
                        break
                    frame_data = raw[idx:]
                    # 确定帧类型
                    frame_type = 'unknown'
                    if b'itb3.0' in frame_data[:100]:
                        frame_type = 'itb3'
                    elif b'tb3.0' in frame_data[:100]:
                        frame_type = 'tb3'
                    elif b'tb1.0' in frame_data[:100] or b'ltb1.0' in frame_data[:100]:
                        frame_type = 'tb1'
                    elif b'JiTu' in frame_data[:200]:
                        frame_type = 'jitu'
                    elif frame_data[37:39] == b'{"':
                        frame_type = 'json'

                    frames.append({
                        'type': frame_type,
                        'data': frame_data,
                        'len': len(frame_data),
                        'time': float(pkt.time) if hasattr(pkt, 'time') else 0,
                        'src': ip.src,
                        'dst': ip.dst,
                    })
                    pos = idx + 4

    return frames


def analyze_tb3_frames(tb3_frames):
    """分析 tb3.0 帧结构"""
    print(f"\n{'='*70}")
    print(f"tb3.0 帧分析 ({len(tb3_frames)} frames)")
    print(f"{'='*70}")

    # 解析 tb3.0 头部
    for i, frame in enumerate(tb3_frames[:3]):
        data = frame['data']
        print(f"\n--- Frame {i} ({frame['len']}B) ---")

        # 找 tb3.0 marker
        pos = data.find(b'tb3.0')
        if pos == -1:
            continue

        print(f"  tb3.0 marker at offset: {pos}")

        # 解析头部 (参考 tb1.0 结构)
        hdr = data[pos:]
        if len(hdr) < 40:
            continue

        print(f"  Header hex (first 48 bytes): {hdr[:48].hex()}")

        # 尝试解析
        p = 6  # skip "tb3.0\0"
        count = struct.unpack_from('<I', hdr, p)[0]; p += 4
        reserved1 = struct.unpack_from('<I', hdr, p)[0]; p += 4
        reserved2 = struct.unpack_from('<I', hdr, p)[0]; p += 4
        struct_size = struct.unpack_from('<H', hdr, p)[0]; p += 2
        market = struct.unpack_from('<H', hdr, p)[0]; p += 2
        flags = struct.unpack_from('<I', hdr, p)[0]; p += 4
        unk1 = struct.unpack_from('<I', hdr, p)[0]; p += 4
        ts1 = struct.unpack_from('<I', hdr, p)[0]; p += 4
        unk2 = struct.unpack_from('<H', hdr, p)[0]; p += 2
        ts2 = struct.unpack_from('<I', hdr, p)[0]; p += 4

        print(f"  count={count}, struct_size={struct_size}, market={market}")
        print(f"  flags={flags:#x}, unk1={unk1}, unk2={unk2}")
        print(f"  ts1={ts1}, ts2={ts2}")

        # 解释 ts2
        if ts2 > 0:
            hours = ts2 // 3600000
            mins = (ts2 % 3600000) // 60000
            secs = (ts2 % 60000) // 1000
            ms = ts2 % 1000
            print(f"  ts2 as ms: {hours:02d}:{mins:02d}:{secs:02d}.{ms:03d}")

        # Data area
        data_area = hdr[p:]
        print(f"  Data area: {len(data_area)} bytes")
        print(f"  Expected: count × struct_size = {count} × {struct_size} = {count * struct_size}")

        # 打印 data area 前 128 字节
        print(f"  Data area hex (first 128B):")
        for j in range(0, min(128, len(data_area)), 32):
            hex_str = data_area[j:j+32].hex()
            formatted = ' '.join(hex_str[k:k+8] for k in range(0, len(hex_str), 8))
            ascii_str = ''.join(chr(b) if 32 <= b < 127 else '.' for b in data_area[j:j+32])
            print(f"    {j:04d}: {formatted}  {ascii_str}")

    return


def search_constants(tb3_frames):
    """在 tb3.0 帧中搜索价格/成交量常量"""
    print(f"\n{'='*70}")
    print(f"搜索特征常量")
    print(f"{'='*70}")

    # 价格编码候选
    price_candidates = {
        'p_x100_le16': struct.pack('<H', int(CLOSE_PRICE * 100)),     # 75 04
        'p_x100_be16': struct.pack('>H', int(CLOSE_PRICE * 100)),     # 04 75
        'p_x100_le32': struct.pack('<I', int(CLOSE_PRICE * 100)),     # 75 04 00 00
        'p_x100_be32': struct.pack('>I', int(CLOSE_PRICE * 100)),     # 00 00 04 75
        'p_x1000_le16': struct.pack('<H', int(CLOSE_PRICE * 1000)),   # 2c 92 (if < 65536)
        'p_x1000_be16': struct.pack('>H', int(CLOSE_PRICE * 1000)),   # 92 2c
        'p_x1000_le32': struct.pack('<I', int(CLOSE_PRICE * 1000)),   # 92 2c 00 00
        'p_x10000_le32': struct.pack('<I', int(CLOSE_PRICE * 10000)), # b4 bd 01 00
        'p_x10000_be32': struct.pack('>I', int(CLOSE_PRICE * 10000)), # 00 01 bd b4
    }

    # 成交量编码候选
    vol_candidates = {
        'v_le16': struct.pack('<H', CLOSE_VOLUME),     # 92 28
        'v_be16': struct.pack('>H', CLOSE_VOLUME),     # 28 92
        'v_le32': struct.pack('<I', CLOSE_VOLUME),     # 92 28 00 00
        'v_be32': struct.pack('>I', CLOSE_VOLUME),     # 00 00 28 92
    }

    # 搜索
    price_hits = defaultdict(list)
    vol_hits = defaultdict(list)

    for frame in tb3_frames:
        data = frame['data']
        # 只搜索 data area (跳过帧头)
        for marker_name in ['itb3.0', 'tb3.0', 'tb1.0', 'ltb1.0']:
            pos = data.find(marker_name.encode())
            if pos != -1:
                # 找到 marker, 搜索 data area
                search_start = pos + 40  # 跳过头部
                if search_start < len(data):
                    search_data = data[search_start:]
                    for name, pattern in price_hits.items():
                        pass  # will search below
                    break

        # 在整个帧中搜索
        for name, pattern in {**price_candidates, **vol_candidates}.items():
            p = 0
            while True:
                idx = data.find(pattern, p)
                if idx == -1:
                    break
                if name.startswith('p_'):
                    price_hits[name].append({'offset': idx, 'frame_len': frame['len']})
                else:
                    vol_hits[name].append({'offset': idx, 'frame_len': frame['len']})
                p = idx + 1

    print(f"\n  价格常量搜索结果:")
    for name, hits in sorted(price_hits.items(), key=lambda x: -len(x[1])):
        if hits:
            offset_counter = Counter(h['offset'] for h in hits)
            print(f"    {name} ({price_candidates.get(name, b'').hex()}): {len(hits)} hits")
            for offset, count in offset_counter.most_common(5):
                print(f"      offset={offset} x{count}")

    print(f"\n  成交量常量搜索结果:")
    for name, hits in sorted(vol_hits.items(), key=lambda x: -len(x[1])):
        if hits:
            offset_counter = Counter(h['offset'] for h in hits)
            print(f"    {name} ({vol_candidates.get(name, b'').hex()}): {len(hits)} hits")
            for offset, count in offset_counter.most_common(5):
                print(f"      offset={offset} x{count}")

    return price_hits, vol_hits


def dump_tb3_data_area(tb3_frames):
    """dump tb3.0 data area 用于人工分析"""
    print(f"\n{'='*70}")
    print(f"tb3.0 Data Area Dump")
    print(f"{'='*70}")

    for i, frame in enumerate(tb3_frames[:5]):
        data = frame['data']
        pos = data.find(b'tb3.0')
        if pos == -1:
            continue

        hdr = data[pos:]
        if len(hdr) < 40:
            continue

        # 解析头部
        p = 6
        count = struct.unpack_from('<I', hdr, p)[0]; p += 4
        p += 4  # reserved
        p += 4  # reserved
        struct_size = struct.unpack_from('<H', hdr, p)[0]; p += 2
        p += 2  # market
        p += 4  # flags
        p += 4  # unk1
        p += 4  # ts1
        p += 2  # unk2
        p += 4  # ts2

        data_area = hdr[p:]

        print(f"\n--- Frame {i}: count={count}, struct_size={struct_size}, data_area={len(data_area)}B ---")

        # 按 struct_size 切分记录
        if struct_size > 0 and count > 0:
            for j in range(min(count, 10)):  # 只打印前10条
                offset = j * struct_size
                if offset + struct_size > len(data_area):
                    break
                record = data_area[offset:offset + struct_size]
                hex_str = record.hex()
                # 格式化为带空格的
                formatted = ' '.join(hex_str[k:k+2] for k in range(0, len(hex_str), 2))
                print(f"  Record {j:3d}: {formatted}")

                # 尝试不同的字段解释
                if struct_size >= 12:
                    vals_u16 = [struct.unpack_from('<H', record, k)[0] for k in range(0, min(struct_size-1, 16), 2)]
                    vals_u32 = [struct.unpack_from('<I', record, k)[0] for k in range(0, min(struct_size-12, 16), 4)]
                    print(f"           u16: {vals_u16}")
                    print(f"           u32: {vals_u32}")
                    # 尝试解释为价格
                    for k in range(0, min(struct_size-1, 16), 2):
                        v = struct.unpack_from('<H', record, k)[0]
                        if 100 < v < 100000:
                            print(f"           offset {k}: u16={v} -> /100={v/100:.2f} /1000={v/1000:.3f}")
                    for k in range(0, min(struct_size-3, 16), 4):
                        v = struct.unpack_from('<I', record, k)[0]
                        if 100 < v < 10000000:
                            print(f"           offset {k}: u32={v} -> /100={v/100:.2f} /1000={v/1000:.3f} /10000={v/10000:.4f}")


def main(pcap_file):
    print(f"Reading: {pcap_file}")
    frames = extract_frames(pcap_file)

    # 按类型分组
    by_type = defaultdict(list)
    for f in frames:
        by_type[f['type']].append(f)

    print(f"Total frames: {len(frames)}")
    for t, fs in sorted(by_type.items(), key=lambda x: -len(x[1])):
        print(f"  {t}: {len(fs)} frames")

    # 分析 tb3.0
    if 'tb3' in by_type:
        analyze_tb3_frames(by_type['tb3'])
        dump_tb3_data_area(by_type['tb3'])
        search_constants(by_type['tb3'])

    # 分析 tb1.0
    if 'tb1' in by_type:
        analyze_tb3_frames(by_type['tb1'])  # 复用同样的分析逻辑
        dump_tb3_data_area(by_type['tb1'])


if __name__ == "__main__":
    pcap_file = sys.argv[1] if len(sys.argv) > 1 else PCAP_FILE
    main(pcap_file)
