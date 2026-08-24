#!/usr/bin/env python3
"""
同花顺 L2 协议字段偏移暴力破解
================================

利用已知特征常量反推协议格式:
1. 成交量特征: 15:00 集合竞价 10386手 → 搜索 0x2892 / 0x9228
2. 价格特征: 14:57-15:00 收盘价 11.41 → 搜索 1141 (×100) / 114100 (×10000)
3. 方向特征: 在价格/成交量附近找跳变字节
4. 时间特征: 高精度时间戳 = 日内分钟 + 毫秒偏移

用法:
  python analyze_pcap_offsets.py D:/stock/data/000001_1432.pcapng
"""

import sys
import struct
from collections import Counter, defaultdict
from scapy.all import rdpcap, TCP, Raw, IP

# ==================== 配置 ====================

PCAP_FILE = r"D:/stock/data/000001_1432.pcapng"
STOCK_CODE = "000001"
CLOSE_PRICE = 11.41
CLOSE_VOLUME = 10386  # 集合竞价大单

# 价格编码候选
PRICE_X100 = int(CLOSE_PRICE * 100)       # 1141
PRICE_X1000 = int(CLOSE_PRICE * 1000)     # 11410
PRICE_X10000 = int(CLOSE_PRICE * 10000)   # 114100

# 成交量编码候选
VOL = CLOSE_VOLUME  # 10386

# 时间范围 (从抓包元数据推断)
# 14:57:00 - 15:00:30 是尾盘集合竞价时段
TARGET_START = "14:57:00"
TARGET_END = "15:00:30"


# ==================== 辅助函数 ====================

def time_to_str(pkt_time):
    """将 scapy 时间戳转为 HH:MM:SS.mmm"""
    import datetime
    dt = datetime.datetime.fromtimestamp(float(pkt_time))
    return dt.strftime("%H:%M:%S.%f")[:12]


def extract_tcp_payloads(pcap_file):
    """从 pcap 提取所有 TCP payload"""
    print(f"[1] 读取 pcap: {pcap_file}")
    packets = rdpcap(pcap_file)
    print(f"    共 {len(packets)} 个包")

    payloads = []
    for i, pkt in enumerate(packets):
        if pkt.haslayer(TCP) and pkt.haslayer(Raw):
            raw = pkt[Raw].load
            # 只取 S→C (服务器推送给客户端)
            tcp = pkt[TCP]
            if tcp.sport == 9528:
                t = float(pkt.time) if hasattr(pkt, 'time') else 0
                payloads.append({
                    'idx': i,
                    'time': t,
                    'raw': raw,
                    'len': len(raw),
                    'sport': tcp.sport,
                    'dport': tcp.dport,
                })

    print(f"    S→C 包: {len(payloads)} 个")
    return payloads


def find_magic_frames(payloads):
    """找到包含 FDFDFDFD Magic 的帧"""
    frames = []
    for p in payloads:
        raw = p['raw']
        # 找所有 Magic 位置
        pos = 0
        while True:
            idx = raw.find(b'\xfd\xfd\xfd\xfd', pos)
            if idx == -1:
                break
            frames.append({
                **p,
                'frame_offset': idx,
                'frame_data': raw[idx:],
            })
            pos = idx + 4
    return frames


# ==================== 任务 A: 搜索成交量字段 ====================

def task_a_find_volume(frames):
    """
    任务 A: 在 15:00:00 附近的大包中搜索 10386 (0x2892)
    """
    print("\n" + "=" * 70)
    print("任务 A: 搜索成交量字段 (10386手 = 0x2892)")
    print("=" * 70)

    # 编码候选
    candidates = {
        'u16_le': struct.pack('<H', VOL),       # 92 28
        'u16_be': struct.pack('>H', VOL),       # 28 92
        'u32_le': struct.pack('<I', VOL),       # 92 28 00 00
        'u32_be': struct.pack('>I', VOL),       # 00 00 28 92
    }

    # 筛选大包 (>200 bytes, 可能是尾盘结算包)
    big_frames = [f for f in frames if f['frame_data'] and len(f['frame_data']) > 200]
    print(f"    大包 (>200B): {len(big_frames)} 个")

    # 搜索每个候选
    results = defaultdict(list)
    for frame in big_frames:
        data = frame['frame_data']
        for name, pattern in candidates.items():
            pos = 0
            while True:
                idx = data.find(pattern, pos)
                if idx == -1:
                    break
                results[name].append({
                    'frame_idx': frame['idx'],
                    'offset': idx,
                    'time': frame['time'],
                    'context': data[max(0,idx-4):idx+8].hex(),
                })
                pos = idx + 1

    # 输出结果
    for name, hits in sorted(results.items(), key=lambda x: -len(x[1])):
        print(f"\n    模式 {name} ({candidates[name].hex()}): 命中 {len(hits)} 次")
        if hits:
            # 统计偏移量分布
            offset_counter = Counter(h['offset'] for h in hits)
            print(f"    偏移量分布 (Top 10):")
            for offset, count in offset_counter.most_common(10):
                print(f"      offset={offset:4d} ×{count:3d}  context={hits[0]['context']}")

    return results


# ==================== 任务 B: 搜索价格字段 ====================

def task_b_find_price(frames):
    """
    任务 B: 在 14:57-15:00 时段搜索价格常量 11.41
    """
    print("\n" + "=" * 70)
    print("任务 B: 搜索价格字段 (11.41)")
    print("=" * 70)

    # 价格编码候选
    candidates = {}
    for div, val in [(100, PRICE_X100), (1000, PRICE_X1000), (10000, PRICE_X10000)]:
        candidates[f'u16_le_x{div}'] = struct.pack('<H', val) if val < 65536 else None
        candidates[f'u16_be_x{div}'] = struct.pack('>H', val) if val < 65536 else None
        candidates[f'u32_le_x{div}'] = struct.pack('<I', val)
        candidates[f'u32_be_x{div}'] = struct.pack('>I', val)
    # 移除 None
    candidates = {k: v for k, v in candidates.items() if v is not None}

    print(f"    价格编码候选:")
    for name, pat in candidates.items():
        print(f"      {name}: {pat.hex()}")

    # 筛选包含 itb3.0 / tb1.0 / ltb1.0 的帧
    binary_frames = []
    for f in frames:
        data = f['frame_data']
        for marker in [b'itb3.0', b'tb1.0', b'ltb1.0', b'tb3.0']:
            if marker in data:
                binary_frames.append(f)
                break

    print(f"    含二进制标记的帧: {len(binary_frames)} 个")

    # 搜索每个候选
    results = defaultdict(list)
    for frame in binary_frames:
        data = frame['frame_data']
        for name, pattern in candidates.items():
            pos = 0
            while True:
                idx = data.find(pattern, pos)
                if idx == -1:
                    break
                results[name].append({
                    'frame_idx': frame['idx'],
                    'offset': idx,
                    'time': frame['time'],
                    'context': data[max(0,idx-4):idx+8].hex(),
                })
                pos = idx + 1

    # 输出结果
    for name, hits in sorted(results.items(), key=lambda x: -len(x[1])):
        print(f"\n    模式 {name} ({candidates[name].hex()}): 命中 {len(hits)} 次")
        if hits:
            offset_counter = Counter(h['offset'] for h in hits)
            print(f"    偏移量分布 (Top 10):")
            for offset, count in offset_counter.most_common(10):
                print(f"      offset={offset:4d} ×{count:3d}")

    return results


# ==================== 任务 C: 搜索方向字段 ====================

def task_c_find_direction(frames, price_offset=None, vol_offset=None):
    """
    任务 C: 在价格/成交量附近搜索方向字节
    """
    print("\n" + "=" * 70)
    print("任务 C: 搜索买卖方向字段")
    print("=" * 70)

    # 筛选含 JiTu 的帧 (JSON 格式)
    jitu_frames = []
    for f in frames:
        data = f['frame_data']
        if b'JiTu' in data or b'"signal"' in data:
            jitu_frames.append(f)

    print(f"    含 JiTu 的帧: {len(jitu_frames)} 个")

    # 分析 JiTu 中的 signal 值
    import json
    signal_stats = Counter()
    for f in jitu_frames:
        data = f['frame_data']
        # 找 JSON
        start = data.find(b'{"')
        if start == -1:
            continue
        try:
            json_str = data[start:].decode('utf-8', errors='replace')
            # 找配对的 }
            depth = 0
            end = 0
            for i, c in enumerate(json_str):
                if c == '{':
                    depth += 1
                elif c == '}':
                    depth -= 1
                    if depth == 0:
                        end = i + 1
                        break
            if end > 0:
                obj = json.loads(json_str[:end])
                for item in obj.get('JiTu', []):
                    value_str = item.get('value', '[]')
                    signals = json.loads(value_str)
                    for sig in signals:
                        signal_stats[sig.get('signal', '?')] += 1
        except:
            pass

    print(f"    JiTu signal 统计: {dict(signal_stats)}")
    print(f"    Tu(卖)={signal_stats.get('Tu',0)}  Ji(买)={signal_stats.get('Ji',0)}")

    # 在原始数据中搜索方向特征字节
    # 常见编码: B=0x42, S=0x53, 0x00/0x01, 0x01/0x02
    print(f"\n    搜索方向字节 (B=0x42, S=0x53):")
    direction_hits = defaultdict(list)
    for f in frames:
        data = f['frame_data']
        # 搜索 B 和 S
        for i in range(len(data)):
            if data[i] == 0x42:
                direction_hits['B'].append(i)
            elif data[i] == 0x53:
                direction_hits['S'].append(i)

    for name, hits in direction_hits.items():
        if hits:
            offset_counter = Counter(hits)
            print(f"    方向 '{name}' (0x{ord(name):02x}): 出现 {len(hits)} 次")
            print(f"      偏移量分布 (Top 10):")
            for offset, count in offset_counter.most_common(10):
                print(f"        offset={offset:4d} ×{count:3d}")

    return signal_stats


# ==================== 任务 D: 破解时间戳 ====================

def task_d_find_timestamp(frames):
    """
    任务 D: 破解高精度时间戳
    """
    print("\n" + "=" * 70)
    print("任务 D: 破解高精度时间戳")
    print("=" * 70)

    # 分析帧头中的 time 字段
    # 帧头结构: Magic(4) + MsgID(8) + Type(2) + 0(1) + Seq(1) + PayloadLen(4) + ...
    # 偏移 16-19 是 PayloadLen
    # 偏移 20-35 是保留字段

    # 收集所有帧的头部字段
    time_candidates = defaultdict(list)

    for f in frames:
        data = f['frame_data']
        if len(data) < 40:
            continue

        # 读取各个可能的 time 字段
        # 偏移 16: PayloadLen (4 bytes)
        payload_len = struct.unpack_from('<I', data, 16)[0]

        # 偏移 20-23: 可能是时间相关
        val_20 = struct.unpack_from('<I', data, 20)[0]
        # 偏移 24-27
        val_24 = struct.unpack_from('<I', data, 24)[0]
        # 偏移 28-31
        val_28 = struct.unpack_from('<I', data, 28)[0]
        # 偏移 32-35
        val_32 = struct.unpack_from('<I', data, 32)[0]
        # 偏移 36-39
        val_36 = struct.unpack_from('<I', data, 36)[0]

        time_candidates['offset_20'].append(val_20)
        time_candidates['offset_24'].append(val_24)
        time_candidates['offset_28'].append(val_28)
        time_candidates['offset_32'].append(val_32)
        time_candidates['offset_36'].append(val_36)

    print(f"    帧头字段值分布:")
    for name, vals in time_candidates.items():
        counter = Counter(vals)
        top5 = counter.most_common(5)
        print(f"    {name}: 唯一值={len(counter)}, Top5={top5}")

    # 分析 tb1.0 中的时间戳
    print(f"\n    tb1.0 头部时间戳分析:")
    tb1_timestamps = []
    for f in frames:
        data = f['frame_data']
        for marker in [b'tb1.0', b'ltb1.0']:
            pos = data.find(marker)
            if pos != -1:
                tb1_data = data[pos:]
                if len(tb1_data) >= 40:
                    # 头部: marker(6) + count(4) + reserved(4) + reserved(4) +
                    #       struct_size(2) + market(2) + flags(4) + unk1(4) +
                    #       ts1(4) + unk2(2) + ts2(4)
                    ts1 = struct.unpack_from('<I', tb1_data, 30)[0]
                    ts2 = struct.unpack_from('<I', tb1_data, 36)[0]
                    count = struct.unpack_from('<I', tb1_data, 6)[0]
                    struct_size = struct.unpack_from('<H', tb1_data, 18)[0]
                    market = struct.unpack_from('<H', tb1_data, 20)[0]
                    tb1_timestamps.append({
                        'ts1': ts1,
                        'ts2': ts2,
                        'count': count,
                        'struct_size': struct_size,
                        'market': market,
                    })

    if tb1_timestamps:
        ts1_counter = Counter(t['ts1'] for t in tb1_timestamps)
        ts2_counter = Counter(t['ts2'] for t in tb1_timestamps)
        print(f"    tb1.0 ts1 (offset 30): 唯一值={len(ts1_counter)}, Top5={ts1_counter.most_common(5)}")
        print(f"    tb1.0 ts2 (offset 36): 唯一值={len(ts2_counter)}, Top5={ts2_counter.most_common(5)}")

        # 尝试解释 ts2 值
        print(f"\n    ts2 值解释尝试:")
        for t in tb1_timestamps[:5]:
            ts2 = t['ts2']
            # 尝试作为毫秒
            hours = ts2 // 3600000
            mins = (ts2 % 3600000) // 60000
            secs = (ts2 % 60000) // 1000
            ms = ts2 % 1000
            print(f"      ts2={ts2} → {hours:02d}:{mins:02d}:{secs:02d}.{ms:03d} (作为ms)")

            # 尝试作为秒
            ts2_ms = ts2 * 1000
            hours2 = ts2_ms // 3600000
            mins2 = (ts2_ms % 3600000) // 60000
            secs2 = (ts2_ms % 60000) // 1000
            print(f"      ts2={ts2} → {hours2:02d}:{mins2:02d}:{secs2:02d} (作为秒×1000)")

    return tb1_timestamps


# ==================== 综合分析 ====================

def comprehensive_analysis(pcap_file):
    """综合分析主函数"""
    print("=" * 70)
    print("同花顺 L2 协议字段偏移暴力破解")
    print(f"目标文件: {pcap_file}")
    print(f"目标股票: {STOCK_CODE} (平安银行)")
    print(f"收盘价: {CLOSE_PRICE}, 集合竞价量: {CLOSE_VOLUME}")
    print("=" * 70)

    # 1. 读取 pcap
    payloads = extract_tcp_payloads(pcap_file)
    frames = find_magic_frames(payloads)
    print(f"    含 Magic 的帧: {len(frames)} 个")

    # 2. 任务 A: 搜索成交量
    vol_results = task_a_find_volume(frames)

    # 3. 任务 B: 搜索价格
    price_results = task_b_find_price(frames)

    # 4. 任务 C: 搜索方向
    dir_results = task_c_find_direction(frames)

    # 5. 任务 D: 时间戳
    ts_results = task_d_find_timestamp(frames)

    # 6. 输出总结
    print("\n" + "=" * 70)
    print("分析总结")
    print("=" * 70)
    print(f"  成交量搜索: {sum(len(v) for v in vol_results.values())} 次命中")
    print(f"  价格搜索: {sum(len(v) for v in price_results.values())} 次命中")
    print(f"  方向统计: Tu(卖)={dir_results.get('Tu',0)}, Ji(买)={dir_results.get('Ji',0)}")
    print(f"  tb1.0 帧数: {len(ts_results)}")


# ==================== 入口 ====================

if __name__ == "__main__":
    pcap_file = sys.argv[1] if len(sys.argv) > 1 else PCAP_FILE
    comprehensive_analysis(pcap_file)
