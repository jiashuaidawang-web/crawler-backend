#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
实时 itb3.0 (10档盘口) TCP 流解析器

使用场景:
  1. 实时监听 TCP 9528 端口
  2. 解析 itb3.0 帧
  3. 提取买卖十档数据
  4. 输出到 Redis/控制台/文件

协议结构:
  Magic(4) + MsgID(8) + Type(2) + Zero(1) + Seq(1) + PayloadLen(4) + Reserved(16) = 36 bytes
  itb3.0\0 (7 bytes)
  DataHeader (count + marker + padding + header fields)
  FieldData: 24 XX YY YY (field marker + type + u16_le value)

字段类型 (已识别):
  0x00: 标识字段 (256, 8326)
  0x01-0x09: 价格/成交量相关
  0x0a, 0x0d, 0x13, 0x22, 0x30, 0x31, 0x56, 0x59: 盘口数据

价格候选: 1040(10.40), 1044(10.44), 1174(11.74), 1176(11.76), 1190(11.90)
成交量候选: 256, 2189, 8192, 8326
"""

import sys
import struct
import asyncio
import time
from datetime import datetime
from collections import defaultdict

# 配置
SERVER_HOST = "139.159.194.69"
SERVER_PORT = 9528
MAGIC = b'\xfd\xfd\xfd\xfd'
ITB_MARKER = b'itb3.0'

# 帧头大小
FRAME_HEADER_SIZE = 36  # Magic(4) + MsgID(8) + Type(2) + Zero(1) + Seq(1) + PayloadLen(4) + Reserved(16)
ITB_MARKER_SIZE = 7     # 'itb3.0\0'
DATA_HEADER_SIZE = 28   # Count(4) + Marker(4) + Padding(4) + HeaderFields(16)


class Itb30FrameParser:
    """itb3.0 帧解析器"""

    def __init__(self):
        self.buffer = bytearray()
        self.frame_count = 0
        self.error_count = 0

    def feed_data(self, data):
        """喂入 TCP 数据, 返回解析出的帧列表"""
        self.buffer.extend(data)
        frames = []

        while True:
            frame = self._try_parse_frame()
            if frame is None:
                break
            frames.append(frame)

        return frames

    def _try_parse_frame(self):
        """尝试从 buffer 中解析一帧"""
        buf = self.buffer

        # 1. 查找 Magic
        magic_pos = buf.find(MAGIC)
        if magic_pos < 0:
            # 保留最后 3 字节 (可能是不完整的 Magic)
            if len(buf) > 3:
                self.buffer = buf[-3:]
            return None

        # 跳过 Magic 之前的数据
        if magic_pos > 0:
            buf = buf[magic_pos:]
            self.buffer = buf

        # 2. 检查是否有足够的帧头
        if len(buf) < FRAME_HEADER_SIZE:
            return None

        # 3. 解析帧头
        msg_id = buf[4:12].decode('ascii', errors='replace')
        msg_type = struct.unpack_from('<H', buf, 12)[0]
        seq = buf[15]
        payload_len = struct.unpack_from('<I', buf, 16)[0]

        # 4. 查找 itb3.0 标记
        itb_pos = buf.find(ITB_MARKER, FRAME_HEADER_SIZE)
        if itb_pos < 0:
            # 没有找到 itb3.0 标记, 跳过这个 Magic
            self.buffer = buf[4:]
            return None

        # 5. 计算帧的总长度
        # 帧头(36) + itb3.0标记(7) + 数据区
        data_start = itb_pos + ITB_MARKER_SIZE

        # 检查是否有足够的数据
        if len(buf) < data_start + 12:
            return None

        # 解析 count
        count = struct.unpack_from('<I', buf, data_start)[0]

        # 估算数据区大小 (每个字段 4 字节: 24 XX YY YY)
        estimated_data_size = DATA_HEADER_SIZE + count * 4

        # 检查是否有足够的数据
        if len(buf) < data_start + estimated_data_size:
            return None

        # 6. 解析完整帧
        frame_data = bytes(buf[:data_start + estimated_data_size])

        # 从 buffer 中移除已解析的帧
        self.buffer = buf[len(frame_data):]

        # 7. 解析帧内容
        try:
            parsed = self._parse_frame_content(frame_data)
            self.frame_count += 1
            return parsed
        except Exception as e:
            self.error_count += 1
            return {
                'error': str(e),
                'raw': frame_data.hex(),
            }

    def _parse_frame_content(self, data):
        """解析帧内容"""
        itb_pos = data.find(ITB_MARKER)
        data_start = itb_pos + ITB_MARKER_SIZE

        # 解析帧头
        msg_id = data[4:12].decode('ascii', errors='replace')
        msg_type = struct.unpack_from('<H', data, 12)[0]
        seq = data[15]

        # 解析数据头
        count = struct.unpack_from('<I', data, data_start)[0]
        marker = struct.unpack_from('<I', data, data_start + 4)[0]
        padding = struct.unpack_from('<I', data, data_start + 8)[0]

        # 解析 header 字段
        header_fields = []
        for i in range(4):  # 4 个 header 字段
            offset = data_start + 12 + i * 4
            if offset + 4 <= len(data):
                val = struct.unpack_from('<I', data, offset)[0]
                header_fields.append(val)

        # 解析 24 XX YY YY 字段
        fields = []
        field_types = defaultdict(list)

        pos = data_start + DATA_HEADER_SIZE
        while pos < len(data) - 3:
            if data[pos] == 0x24:
                field_type = data[pos + 1]
                field_val = struct.unpack_from('<H', data, pos + 2)[0]
                fields.append({
                    'offset': pos - data_start,
                    'type': field_type,
                    'value': field_val,
                })
                field_types[field_type].append(field_val)
                pos += 4
            else:
                break

        # 提取价格/成交量
        prices = []
        volumes = []
        for f in fields:
            if 1000 <= f['value'] <= 1200:
                prices.append(f['value'])
            elif 100 <= f['value'] <= 10000:
                volumes.append(f['value'])

        return {
            'timestamp': datetime.now().strftime('%H:%M:%S.%f')[:-3],
            'msg_id': msg_id,
            'msg_type': f"0x{msg_type:04x}",
            'seq': seq,
            'count': count,
            'marker': marker,
            'header_fields': header_fields,
            'fields': fields,
            'field_summary': {f"0x{k:02x}": v for k, v in field_types.items()},
            'prices': [f"{p/100:.2f}" for p in prices],
            'price_raw': prices,
            'volumes': volumes,
            'stock_code': '0000001',
        }


class RealtimeItb30Client:
    """实时 itb3.0 客户端"""

    def __init__(self, host, port, callback=None):
        self.host = host
        self.port = port
        self.callback = callback or self._default_callback
        self.parser = Itb30FrameParser()
        self.running = False

    def _default_callback(self, frame):
        """默认回调: 打印帧信息"""
        if 'error' in frame:
            print(f"[ERROR] {frame['error']}")
            return

        ts = frame['timestamp']
        prices = frame.get('prices', [])
        volumes = frame.get('volumes', [])
        stock = frame.get('stock_code', 'N/A')

        print(f"[{ts}] {stock} | Prices: {', '.join(prices)} | Volumes: {volumes}")

    async def connect_and_listen(self):
        """连接到服务器并监听数据"""
        self.running = True

        while self.running:
            try:
                reader, writer = await asyncio.open_connection(self.host, self.port)
                print(f"Connected to {self.host}:{self.port}")

                while self.running:
                    data = await reader.read(4096)
                    if not data:
                        print("Connection closed by server")
                        break

                    frames = self.parser.feed_data(data)
                    for frame in frames:
                        self.callback(frame)

            except ConnectionRefusedError:
                print(f"Connection refused. Retrying in 5s...")
                await asyncio.sleep(5)
            except Exception as e:
                print(f"Error: {e}")
                await asyncio.sleep(5)

    def stop(self):
        self.running = False


class PcapReplayClient:
    """PCAP 回放客户端 (用于测试)"""

    def __init__(self, pcap_file, callback=None):
        self.pcap_file = pcap_file
        self.callback = callback or self._default_callback

    def _default_callback(self, frame):
        """默认回调: 打印帧信息"""
        if 'error' in frame:
            print(f"[ERROR] {frame['error']}")
            return

        ts = frame['timestamp']
        prices = frame.get('prices', [])
        volumes = frame.get('volumes', [])
        stock = frame.get('stock_code', 'N/A')

        print(f"[{ts}] {stock} | Prices: {', '.join(prices)} | Volumes: {volumes}")

    def replay(self):
        """回放 pcap 文件"""
        from scapy.all import rdpcap, TCP, Raw

        print(f"Replaying {self.pcap_file}...")
        packets = rdpcap(self.pcap_file)

        parser = Itb30FrameParser()
        frame_count = 0

        for i, pkt in enumerate(packets):
            if pkt.haslayer(TCP) and pkt.haslayer(Raw):
                tcp = pkt[TCP]
                if tcp.sport == 9528:
                    raw = pkt[Raw].load
                    if ITB_MARKER in raw:
                        frames = parser.feed_data(raw)
                        for frame in frames:
                            self.callback(frame)
                            frame_count += 1

        print(f"\nReplayed {frame_count} itb3.0 frames")


def main():
    import argparse

    parser = argparse.ArgumentParser(description='itb3.0 实时解析器')
    parser.add_argument('--mode', choices=['replay', 'live'], default='replay',
                        help='运行模式: replay (回放pcap) 或 live (实时监听)')
    parser.add_argument('--pcap', default=r'D:/stock/data/000001_1432.pcapng',
                        help='pcap 文件路径 (replay 模式)')
    parser.add_argument('--host', default=SERVER_HOST,
                        help='服务器地址 (live 模式)')
    parser.add_argument('--port', type=int, default=SERVER_PORT,
                        help='服务器端口 (live 模式)')
    parser.add_argument('--output', default=None,
                        help='输出文件路径 (CSV)')

    args = parser.parse_args()

    if args.mode == 'replay':
        client = PcapReplayClient(args.pcap)
        client.replay()
    elif args.mode == 'live':
        client = RealtimeItb30Client(args.host, args.port)
        try:
            asyncio.run(client.connect_and_listen())
        except KeyboardInterrupt:
            client.stop()
            print("\nStopped.")


if __name__ == "__main__":
    main()
