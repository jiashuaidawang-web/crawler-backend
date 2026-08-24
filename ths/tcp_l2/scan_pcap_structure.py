#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
扫描 pcap 文件结构, 找出所有 TCP 9528 通信的 IP 和端口
"""

import sys
import struct
from collections import Counter, defaultdict
from scapy.all import rdpcap, TCP, Raw, IP

PCAP_FILE = r"D:/stock/data/000001_1432.pcapng"

def scan_pcap(pcap_file):
    print(f"[1] Reading pcap: {pcap_file}")
    packets = rdpcap(pcap_file)
    print(f"    Total packets: {len(packets)}")

    # 统计所有 TCP 9528 通信
    port_9528_stats = Counter()
    ip_pairs = Counter()
    payload_sizes = []

    for pkt in packets:
        if pkt.haslayer(TCP) and pkt.haslayer(IP):
            ip = pkt[IP]
            tcp = pkt[TCP]
            if tcp.dport == 9528 or tcp.sport == 9528:
                direction = "C->S" if tcp.dport == 9528 else "S->C"
                key = f"{ip.src}:{tcp.sport} -> {ip.dst}:{tcp.dport} ({direction})"
                port_9528_stats[key] += 1
                ip_pairs[(ip.src, ip.dst)] += 1
                if pkt.haslayer(Raw):
                    payload_sizes.append((direction, len(pkt[Raw].load)))

    print(f"\n[2] TCP 9528 communication:")
    for key, count in port_9528_stats.most_common(20):
        print(f"    {key}: {count} packets")

    print(f"\n[3] IP pairs:")
    for (src, dst), count in ip_pairs.most_common(20):
        print(f"    {src} -> {dst}: {count}")

    print(f"\n[4] Payload size distribution:")
    c2s_sizes = [s for d, s in payload_sizes if d == "C->S"]
    s2c_sizes = [s for d, s in payload_sizes if d == "S->C"]
    if c2s_sizes:
        print(f"    C->S: min={min(c2s_sizes)}, max={max(c2s_sizes)}, avg={sum(c2s_sizes)//len(c2s_sizes)}")
        c2s_counter = Counter(c2s_sizes)
        print(f"    C->S top sizes: {c2s_counter.most_common(10)}")
    if s2c_sizes:
        print(f"    S->C: min={min(s2c_sizes)}, max={max(s2c_sizes)}, avg={sum(s2c_sizes)//len(s2c_sizes)}")
        s2c_counter = Counter(s2c_sizes)
        print(f"    S->C top sizes: {s2c_counter.most_common(10)}")

    # 分析 S->C 大包的内容
    print(f"\n[5] S->C large packets (>100B) content analysis:")
    large_s2c = []
    for pkt in packets:
        if pkt.haslayer(TCP) and pkt.haslayer(IP) and pkt.haslayer(Raw):
            ip = pkt[IP]
            tcp = pkt[TCP]
            if tcp.sport == 9528 and pkt.haslayer(Raw):
                raw = pkt[Raw].load
                if len(raw) > 100:
                    large_s2c.append({
                        'src': ip.src,
                        'dst': ip.dst,
                        'sport': tcp.sport,
                        'dport': tcp.dport,
                        'len': len(raw),
                        'raw': raw,
                        'time': float(pkt.time) if hasattr(pkt, 'time') else 0,
                    })

    print(f"    S->C large packets: {len(large_s2c)}")

    # 分析大包中的 Magic 和标记
    magic_counter = Counter()
    marker_counter = Counter()
    for p in large_s2c:
        raw = p['raw']
        # 找 Magic
        if b'\xfd\xfd\xfd\xfd' in raw:
            magic_counter['FDFDFDFD'] += 1
        # 找标记
        for marker in [b'itb3.0', b'tb1.0', b'ltb1.0', b'tb3.0', b'JiTu', b'frame']:
            if marker in raw:
                marker_counter[marker.decode()] += 1

    print(f"    Magic markers: {dict(magic_counter)}")
    print(f"    Data markers: {dict(marker_counter)}")

    # 打印前 5 个大包的 hex dump
    print(f"\n[6] First 5 large S->C packets (first 128 bytes hex):")
    for i, p in enumerate(large_s2c[:5]):
        raw = p['raw'][:128]
        print(f"\n    Packet {i}: {p['src']}:{p['sport']} -> {p['dst']}:{p['dport']} ({p['len']}B)")
        # 打印 hex (每行 32 bytes)
        for j in range(0, len(raw), 32):
            hex_str = raw[j:j+32].hex()
            # 格式化为 4-byte groups
            formatted = ' '.join(hex_str[k:k+8] for k in range(0, len(hex_str), 8))
            ascii_str = ''.join(chr(b) if 32 <= b < 127 else '.' for b in raw[j:j+32])
            print(f"      {j:04d}: {formatted}  {ascii_str}")

    return large_s2c


if __name__ == "__main__":
    pcap_file = sys.argv[1] if len(sys.argv) > 1 else PCAP_FILE
    scan_pcap(pcap_file)
