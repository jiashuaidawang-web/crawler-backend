#!/usr/bin/env python3
"""
同花顺 L2 实时数据引擎 v2
基于 pcap 逆向的协议实现

协议:
  1. 连接 TCP 139.159.194.69:9528
  2. 发送登录帧 (signapp=android&...)
  3. 发送 page 订阅请求 ([frame] id=9001 pageList=10056 stockcode=xxx)
  4. 接收数据: 二进制盘口(itb3.0) + JSON逐笔(JiTu)
  5. 解析并写入 Redis
"""

import asyncio
import struct
import re
import time
import json
import logging
from datetime import datetime
from typing import Dict, List, Optional, Set
from dataclasses import dataclass, field

# ==================== 配置 ====================

@dataclass
class Config:
    """系统配置"""
    # 行情服务器（从 pcap 确认）
    SERVER_HOST: str = "139.159.194.69"
    SERVER_PORT: int = 9528

    # Redis
    REDIS_HOST: str = "127.0.0.1"
    REDIS_PORT: int = 6379
    REDIS_DB: int = 0
    REDIS_PASSWORD: str = ""

    # 性能
    FLUSH_INTERVAL_MS: int = 100  # 刷新间隔(ms)
    RECONNECT_INTERVAL: int = 5   # 重连间隔(s)

    # 数据
    TICK_MAX_LEN: int = 10000     # 每支股票 tick 流最大长度
    QUOTE_TTL: int = 60           # quote 过期时间(s)
    STATUS_TTL: int = 60          # status 过期时间(s)


# ==================== 数据模型 ====================

@dataclass
class Tick:
    """逐笔成交"""
    timestamp: int          # 时间戳 ms
    price: float           # 成交价
    volume: int            # 成交量(手)
    direction: str         # B=买 S=卖
    amount: float = 0.0    # 成交金额
    stock_code: str = ""   # 股票代码

    def to_redis(self) -> dict:
        return {
            "t": str(self.timestamp),
            "p": str(self.price),
            "v": str(self.volume),
            "d": self.direction,
            "a": str(self.amount),
        }


@dataclass
class Quote:
    """盘口快照"""
    code: str = ""
    last_price: float = 0.0
    open_price: float = 0.0
    high: float = 0.0
    low: float = 0.0
    prev_close: float = 0.0
    volume: int = 0
    amount: float = 0.0
    bid_prices: List[float] = field(default_factory=lambda: [0.0]*10)
    bid_volumes: List[int] = field(default_factory=lambda: [0]*10)
    ask_prices: List[float] = field(default_factory=lambda: [0.0]*10)
    ask_volumes: List[int] = field(default_factory=lambda: [0]*10)
    timestamp: int = 0

    def to_redis(self) -> dict:
        """转为 Redis Hash 字段"""
        d = {
            "code": self.code,
            "last_price": str(self.last_price),
            "open": str(self.open_price),
            "high": str(self.high),
            "low": str(self.low),
            "prev_close": str(self.prev_close),
            "volume": str(self.volume),
            "amount": str(self.amount),
            "timestamp": str(self.timestamp),
            "update_time": datetime.now().strftime("%Y-%m-%d %H:%M:%S"),
        }
        for i in range(10):
            d["bid%d_p" % (i+1)] = str(self.bid_prices[i])
            d["bid%d_v" % (i+1)] = str(self.bid_volumes[i])
            d["ask%d_p" % (i+1)] = str(self.ask_prices[i])
            d["ask%d_v" % (i+1)] = str(self.ask_volumes[i])
        return d


# ==================== 协议解析器 ====================

class THSProtocolParser:
    """同花顺 TCP 私有协议解析器"""

    MAGIC = b"\xfd\xfd\xfd\xfd"
    TYPE_C2S = 0x001c  # 客户端→服务器
    TYPE_S2C = 0x0018  # 服务器→客户端

    def __init__(self):
        self.buffer = bytearray()
        self.msg_counter = 0
        self.seq_counter = 0

    def feed(self, data: bytes):
        """喂入数据"""
        self.buffer.extend(data)

    def parse_frames(self) -> List[dict]:
        """解析所有完整帧（支持一包多帧）"""
        frames = []

        while True:
            # 找 Magic
            idx = self.buffer.find(self.MAGIC)
            if idx == -1:
                if len(self.buffer) > 2048:
                    self.buffer = self.buffer[-1024:]
                break

            if idx > 0:
                self.buffer = self.buffer[idx:]

            # 至少需要 48 字节头部
            if len(self.buffer) < 48:
                break

            # 找下一帧的 Magic 来确定当前帧边界
            next_magic = self.buffer.find(self.MAGIC, 4)
            if next_magic == -1:
                # 没有下一帧，检查是否有足够数据
                if len(self.buffer) < 48 + 10:
                    break  # 等待更多数据
                frame_end = len(self.buffer)
            else:
                frame_end = next_magic

            frame_data = bytes(self.buffer[:frame_end])
            parsed = self._parse_frame(frame_data)
            if parsed:
                frames.append(parsed)

            # 消费掉已解析的帧
            self.buffer = self.buffer[frame_end:]

        return frames

    def _parse_frame(self, data: bytes) -> Optional[dict]:
        """解析单帧（支持变长帧头）"""
        if len(data) < 37:
            return None

        magic = data[0:4]
        if magic != self.MAGIC:
            return None

        msg_id = data[4:12].decode("ascii", errors="replace")
        frame_type = struct.unpack("<H", data[12:14])[0]
        seq = data[15]
        payload_len = struct.unpack_from('<I', data, 16)[0] if len(data) >= 20 else 0

        result = {
            "msg_id": msg_id,
            "type": frame_type,
            "seq": seq,
            "payload_len": payload_len,
            "_raw": data,  # 保存原始数据用于调试
        }

        # 确定 payload 起始偏移（帧头可能是 37 或 40 字节）
        # 通过查找已知标记来确定实际 payload 位置
        payload_offset = self._find_payload_offset(data)
        result["payload_offset"] = payload_offset

        payload = data[payload_offset:]
        if not payload:
            return result

        # 解析 payload 内容
        self._parse_payload(payload, result)

        # 消费长度 = 整个帧
        result["_consumed"] = len(data)

        return result

    def _find_payload_offset(self, data: bytes) -> int:
        """确定 payload 起始偏移（帧头可能是 37 或 40 字节）"""
        if len(data) < 37:
            return len(data)

        # 尝试偏移 37（JSON 帧常见）
        if data[37:39] == b'{"':
            return 37

        # 尝试偏移 40（itb3.0/tb1.0 帧常见）
        if len(data) >= 40:
            # 检查是否是已知的二进制标记
            marker_pos = data.find(b'itb3.0', 36, 44)
            if marker_pos != -1:
                return marker_pos
            marker_pos = data.find(b'tb1.0', 36, 44)
            if marker_pos != -1:
                return marker_pos
            marker_pos = data.find(b'ltb1.0', 36, 44)
            if marker_pos != -1:
                return marker_pos
            marker_pos = data.find(b'tb3.0', 36, 44)
            if marker_pos != -1:
                return marker_pos

        # 默认 37
        return 37

    def _parse_payload(self, payload: bytes, result: dict):
        """解析 payload 内容"""
        # 1. 找 JSON 数据（JiTu 逐笔 / events）- 从 payload 中找 {
        json_start = payload.find(b'{"')
        if json_start >= 0:
            json_data = self._extract_json(payload[json_start:])
            if json_data:
                result["json"] = json_data

        # 2. 找 itb3.0 盘口数据
        itb_pos = payload.find(b'itb3.0')
        if itb_pos >= 0:
            itb_data = payload[itb_pos:]
            result["itb3"] = itb_data

        # 3. 找 tb1.0 / ltb1.0 格式数据（可能前面有额外字节）
        for marker in [b'ltb1.0', b'tb1.0']:
            tb1_pos = payload.find(marker)
            if tb1_pos >= 0:
                # 包含前面的控制字节
                start = max(0, tb1_pos - 4)
                tb1_data = payload[start:]
                result["tb1"] = tb1_data
                break

        # 4. 找 tb3.0 格式数据
        tb3_pos = payload.find(b'tb3.0')
        if tb3_pos >= 0:
            tb3_data = payload[tb3_pos:]
            result["tb3"] = tb3_data

        # 5. 如果以上都没找到，标记为未知二进制
        if not result.get("json") and not result.get("itb3") and not result.get("tb1") and not result.get("tb3"):
            if len(payload) > 4:
                result["unknown"] = payload

    def _extract_json(self, data: bytes) -> Optional[dict]:
        """从字节中提取 JSON 对象"""
        json_str = data.decode('utf-8', errors='replace')

        # 找配对的 braces
        depth = 0
        end_pos = 0
        start_pos = -1
        for i, c in enumerate(json_str):
            if c == '{':
                if depth == 0:
                    start_pos = i
                depth += 1
            elif c == '}':
                depth -= 1
                if depth == 0:
                    end_pos = i + 1
                    break

        if end_pos > 0 and start_pos >= 0:
            try:
                return json.loads(json_str[start_pos:end_pos])
            except json.JSONDecodeError:
                pass
        return None

    def build_login_frame(self) -> bytes:
        """构建登录帧 - 使用 pcap 原始字节"""
        try:
            from ths_frames import get_login_frame
            return get_login_frame()
        except ImportError:
            logging.warning("ths_frames 模块不存在，使用内置登录帧")
            return self._build_login_frame_fallback()

    def build_subscribe_frame(self, stock_code: str, market: str = "33") -> bytes:
        """构建订阅请求帧 - 使用 pcap 原始帧替换股票代码"""
        try:
            from ths_frames import get_subscribe_frame
            frame = get_subscribe_frame()
            # 替换股票代码
            return self._replace_stock_code(frame, stock_code, market)
        except ImportError:
            logging.warning("ths_frames 模块不存在，使用内置订阅帧")
            return self._build_subscribe_frame_fallback(stock_code, market)

    def _replace_stock_code(self, frame: bytes, stock_code: str, market: str) -> bytes:
        """替换帧中的股票代码"""
        # 找 stockcode= 的位置
        old_code = b"stockcode=002384"
        new_code = ("stockcode=%s" % stock_code).encode('ascii')

        if old_code in frame:
            frame = frame.replace(old_code, new_code)
        else:
            # 尝试通用替换
            import re
            frame = re.sub(b'stockcode=\\d+', new_code, frame)

        # 替换 marketcode
        old_market = b"marketcode=33"
        new_market = ("marketcode=%s" % market).encode('ascii')
        if old_market in frame:
            frame = frame.replace(old_market, new_market)

        return frame

    def _build_login_frame_fallback(self) -> bytes:
        """内置登录帧（备用）"""
        self.msg_counter += 1
        self.seq_counter += 1

        lines = [
            "signapp=android",
            "AppVer=G037.08.426.1.32",
            "for=ths_am_gphone_login",
            "sourceid=714",
            "progid=500",
            "udid=104337010050006",
            "FCSup=0111111111100011111111",
            "SW=720",
            "SH=1280",
            "CA=4",
            "net=1",
            "TP=25102RKBEC",
            "SVN=5d30d11ef7717d0c030e06ae9d3f9ca910d42d16",
            "SDK=28",
            "SDKN=9",
            "IMEI=670084180860401",
            "imsi=104337010050006",
            "CID=714",
            "macA=ce:67:1c:34:8f:c2",
            "SD=1787364000",
            "authver=9",
            "DeviceID=dev_638361358_251_9529",
            "account=mx_431620525",
            "userid=431620525",
            "MT=mt_aval2c1cm",
            "MU=895137210",
            "PWD=d055c6a3d63aceabc767991598b1ee5c",
            "ExtVer=1",
            "UserType=0",
            "psum=3526234581",
            "Signature=UL9vCibzo+zFb0N3Yhtq2NW3M3F8XLddW+85bvwCL95dak",
        ]
        payload = "\r\n".join(lines).encode('ascii')
        return self._build_frame_raw("%08d" % self.msg_counter, self.TYPE_C2S, self.seq_counter, payload)

    def _build_subscribe_frame_fallback(self, stock_code: str, market: str) -> bytes:
        """内置订阅帧（备用）"""
        self.msg_counter += 1
        self.seq_counter += 1

        lines = [
            "[frame]",
            "id=2205",
            "pageList=10063,10064,10028,10066,10068,10056",
            "reqPage=10056",
            "reqPageCount=1",
            "[10056]",
            "id=1214",
            "",
            "stockcode=%s" % stock_code,
            "marketcode=%s" % market,
        ]
        payload = "\r\n".join(lines).encode('ascii')
        return self._build_frame_raw("%08d" % self.msg_counter, self.TYPE_C2S, self.seq_counter, payload)

    def _build_frame_raw(self, msg_id: str, frame_type: int, seq: int, payload: bytes) -> bytes:
        """构建一帧（原始方式）"""
        header = bytearray()
        header += self.MAGIC
        header += msg_id.encode('ascii')
        header += struct.pack("<H", frame_type)
        header += struct.pack("<H", 0)
        header += struct.pack("<I", seq)
        header += struct.pack("<I", 0)
        header += struct.pack("<I", len(payload))
        header += struct.pack("<I", 0)
        header += struct.pack("<I", 0)
        header += struct.pack("<I", 0)
        header += struct.pack("<H", 0xd502)
        header += struct.pack("<H", 0xd502)
        header += struct.pack("<H", 0x0504)
        return bytes(header) + payload


# ==================== L2 数据提取器 ====================

class L2DataExtractor:
    """从解析后的帧中提取 L2 数据"""

    @staticmethod
    def extract_ticks(frame: dict) -> List[Tick]:
        """从帧中提取逐笔成交数据"""
        ticks = []
        json_data = frame.get("json")
        if not json_data:
            return ticks

        # JiTu 格式
        jitu_list = json_data.get("JiTu", [])
        for item in jitu_list:
            stock_code = item.get("stockcode", "")
            value_str = item.get("value", "[]")
            try:
                signals = json.loads(value_str)
                for sig in signals:
                    signal = sig.get("signal", "")
                    tick_time = sig.get("time", 0)
                    direction = "S" if signal == "Tu" else "B" if signal == "Ji" else "U"
                    tick = Tick(
                        timestamp=tick_time * 1000,  # 转为 ms
                        price=0.0,  # JiTu 格式暂时没有价格
                        volume=0,
                        direction=direction,
                        stock_code=stock_code,
                    )
                    ticks.append(tick)
            except (json.JSONDecodeError, TypeError):
                pass

        return ticks

    @staticmethod
    def parse_tb10(frame: dict) -> Optional[dict]:
        """解析 tb1.0 二进制格式，提取股票代码和价格数据"""
        tb1_data = frame.get("tb1")
        if not tb1_data or len(tb1_data) < 40:
            return None

        # 找 marker
        marker_pos = tb1_data.find(b'tb1.0')
        if marker_pos == -1:
            marker_pos = tb1_data.find(b'ltb1.0')
        if marker_pos == -1:
            return None

        data = tb1_data[marker_pos:]
        if len(data) < 40:
            return None

        p = 6  # skip "tb1.0\x00"
        try:
            count = struct.unpack_from('<I', data, p)[0]; p += 4
            struct.unpack_from('<I', data, p)[0]; p += 4  # reserved
            struct.unpack_from('<I', data, p)[0]; p += 4  # reserved
            struct_size = struct.unpack_from('<H', data, p)[0]; p += 2
            market = struct.unpack_from('<H', data, p)[0]; p += 2
            struct.unpack_from('<I', data, p)[0]; p += 4  # flags
            struct.unpack_from('<I', data, p)[0]; p += 4  # unk1
            struct.unpack_from('<I', data, p)[0]; p += 4  # ts1
            struct.unpack_from('<H', data, p)[0]; p += 2  # unk2
            ts2 = struct.unpack_from('<I', data, p)[0]; p += 4
        except struct.error:
            return None

        data_area = data[p:]

        # 提取所有 UTF-16LE 字符串
        strings = []
        i = 0
        while i < len(data_area) - 1:
            if data_area[i+1] == 0 and 0x20 <= data_area[i] < 0x7f:
                start = i
                chars = []
                while i < len(data_area) - 1 and data_area[i+1] == 0 and 0x20 <= data_area[i] < 0x7f:
                    chars.append(chr(data_area[i]))
                    i += 2
                s = ''.join(chars)
                if len(s) >= 3:
                    strings.append(s)
            else:
                i += 1

        # 识别股票代码（第一个6位数字字符串）
        stock_code = None
        prices = []
        volumes = []
        for s in strings:
            if re.match(r'^\d{6}$', s) and stock_code is None:
                stock_code = s
            elif re.match(r'^\d+\.\d+$', s):
                prices.append(s)
            elif re.match(r'^\d{3,}$', s):
                volumes.append(s)

        if not stock_code:
            return None

        return {
            "stock_code": stock_code,
            "prices": prices,
            "volumes": volumes,
            "strings": strings,
            "count": count,
            "struct_size": struct_size,
            "market": market,
            "timestamp": ts2,
            "data_area_len": len(data_area),
        }

    @staticmethod
    def extract_quote(frame: dict) -> Optional[Quote]:
        """从帧中提取盘口数据（itb3.0 格式）"""
        itb_data = frame.get("itb3")
        if not itb_data:
            return None

        # TODO: 解析 itb3.0 二进制格式
        # 目前返回 None，后续实现
        return None

    @staticmethod
    def extract_stock_code(frame: dict) -> Optional[str]:
        """从帧中识别股票代码"""
        json_data = frame.get("json")
        if json_data:
            # 从 JiTu 中提取
            jitu_list = json_data.get("JiTu", [])
            if jitu_list:
                return jitu_list[0].get("stockcode")
            # 从 event 中提取
            event_list = json_data.get("event", [])
            if event_list:
                return event_list[0].get("stockcode")

        # 从 tb1.0 中提取
        tb10 = L2DataExtractor.parse_tb10(frame)
        if tb10:
            return tb10.get("stock_code")

        return None


# ==================== Redis 写入器 ====================

class RedisWriter:
    """Redis 批量写入器"""

    def __init__(self, config: Config):
        self.config = config
        self.redis = None
        self.tick_batch: List[dict] = []
        self.quote_batch: List[dict] = []

    async def connect(self):
        """连接 Redis"""
        from redis.asyncio import Redis as AsyncRedis
        self.redis = AsyncRedis(
            host=self.config.REDIS_HOST,
            port=self.config.REDIS_PORT,
            db=self.config.REDIS_DB,
            password=self.config.REDIS_PASSWORD or None,
        )
        await self.redis.ping()
        logging.info("Redis 连接成功: %s:%d" % (self.config.REDIS_HOST, self.config.REDIS_PORT))

    async def add_tick(self, code: str, tick: Tick):
        """添加 tick 到批量队列"""
        self.tick_batch.append({
            "key": "ths:l2:tick:%s" % code,
            "fields": tick.to_redis(),
        })

    async def add_quote(self, code: str, quote: Quote):
        """添加 quote 到批量队列"""
        quote.code = code
        self.quote_batch.append({
            "key": "ths:l2:quote:%s" % code,
            "fields": quote.to_redis(),
        })

    async def add_tb10(self, code: str, tb10: dict):
        """添加 tb1.0 数据到批量队列"""
        self.quote_batch.append({
            "key": "ths:l2:quote:%s" % code,
            "fields": {
                "code": code,
                "tb10_prices": ",".join(tb10.get("prices", [])),
                "tb10_volumes": ",".join(tb10.get("volumes", [])),
                "tb10_strings": ",".join(tb10.get("strings", [])),
                "tb10_count": str(tb10.get("count", 0)),
                "tb10_data_len": str(tb10.get("data_area_len", 0)),
                "timestamp": str(tb10.get("timestamp", 0)),
                "update_time": datetime.now().strftime("%Y-%m-%d %H:%M:%S"),
            },
        })

    async def flush_ticks(self):
        """批量写入 tick"""
        if not self.tick_batch:
            return

        pipe = self.redis.pipeline()
        for item in self.tick_batch:
            pipe.xadd(item["key"], item["fields"])
            pipe.xtrim(item["key"], maxlen=self.config.TICK_MAX_LEN)
        await pipe.execute()

        count = len(self.tick_batch)
        self.tick_batch.clear()
        if count > 0:
            logging.debug("写入 %d 条 tick" % count)

    async def flush_quotes(self):
        """批量写入 quote"""
        if not self.quote_batch:
            return

        pipe = self.redis.pipeline()
        for item in self.quote_batch:
            pipe.hset(item["key"], mapping=item["fields"])
            pipe.expire(item["key"], self.config.QUOTE_TTL)
        await pipe.execute()

        count = len(self.quote_batch)
        self.quote_batch.clear()
        if count > 0:
            logging.debug("写入 %d 条 quote" % count)

    async def flush_all(self):
        """刷新所有批量队列"""
        await self.flush_ticks()
        await self.flush_quotes()

    async def update_status(self, code: str, status: dict):
        """更新状态"""
        key = "ths:l2:status:%s" % code
        await self.redis.hset(key, mapping=status)
        await self.redis.expire(key, self.config.STATUS_TTL)


# ==================== 股票池管理 ====================

class StockPool:
    """股票池管理"""

    def __init__(self, redis):
        self.redis = redis
        self.pool_key = "ths:l2:pool"
        self._pool: Set[str] = set()

    async def add(self, codes: List[str]):
        """添加股票"""
        if codes:
            await self.redis.sadd(self.pool_key, *codes)
            self._pool.update(codes)
            logging.info("股票池添加: %s" % codes)

    async def remove(self, codes: List[str]):
        """移除股票"""
        if codes:
            await self.redis.srem(self.pool_key, *codes)
            self._pool.difference_update(codes)

    async def get_pool(self) -> Set[str]:
        """获取股票池"""
        members = await self.redis.smembers(self.pool_key)
        self._pool = set(m.decode() if isinstance(m, bytes) else m for m in members)
        return self._pool

    def is_in_pool(self, code: str) -> bool:
        return code in self._pool


# ==================== 主引擎 ====================

class THSL2Engine:
    """同花顺 L2 实时数据引擎"""

    def __init__(self, config: Config = None):
        self.config = config or Config()
        self.parser = THSProtocolParser()
        self.extractor = L2DataExtractor()
        self.writer: Optional[RedisWriter] = None
        self.pool: Optional[StockPool] = None
        self.running = False
        self.stats = {
            "frames": 0,
            "ticks": 0,
            "quotes": 0,
            "tb10": 0,
            "errors": 0,
            "start_time": 0,
        }
        self.subscribed_stocks: Set[str] = set()

    async def start(self):
        """启动引擎"""
        logging.info("=" * 50)
        logging.info("同花顺 L2 实时数据引擎启动")
        logging.info("=" * 50)

        self.running = True
        self.stats["start_time"] = int(time.time())

        # 连接 Redis
        self.writer = RedisWriter(self.config)
        await self.writer.connect()

        # 初始化股票池
        self.pool = StockPool(self.writer.redis)

        # 默认股票池（10支）
        default_pool = [
            "600519",  # 贵州茅台
            "000001",  # 平安银行
            "600667",  # 太极实业
            "300750",  # 宁德时代
            "000651",  # 格力电器
            "601318",  # 中国平安
            "000858",  # 五粮液
            "600036",  # 招商银行
            "002594",  # 比亚迪
            "601012",  # 隆基绿能
        ]
        await self.pool.add(default_pool)

        # 启动 TCP 连接
        await self._tcp_loop()

    async def _tcp_loop(self):
        """TCP 连接主循环"""
        while self.running:
            try:
                logging.info("连接行情服务器: %s:%d" % (self.config.SERVER_HOST, self.config.SERVER_PORT))

                reader, writer = await asyncio.open_connection(
                    self.config.SERVER_HOST,
                    self.config.SERVER_PORT,
                )

                logging.info("TCP 连接成功")

                # 发送登录帧
                login_frame = self.parser.build_login_frame()
                writer.write(login_frame)
                await writer.drain()
                logging.info("已发送登录帧")

                # 等登录响应
                await asyncio.sleep(1)

                # 订阅股票池
                pool = await self.pool.get_pool()
                for code in pool:
                    sub_frame = self.parser.build_subscribe_frame(code)
                    writer.write(sub_frame)
                    await writer.drain()
                    self.subscribed_stocks.add(code)
                    await asyncio.sleep(0.1)

                logging.info("已订阅 %d 支股票" % len(self.subscribed_stocks))

                # 读取数据
                await self._read_loop(reader, writer)

            except ConnectionRefusedError:
                logging.error("连接被拒绝")
            except ConnectionResetError:
                logging.error("连接被重置")
            except asyncio.CancelledError:
                break
            except Exception as e:
                logging.error("TCP 错误: %s" % e)
                self.stats["errors"] += 1

            if self.running:
                self.subscribed_stocks.clear()
                logging.info("%d秒后重连..." % self.config.RECONNECT_INTERVAL)
                await asyncio.sleep(self.config.RECONNECT_INTERVAL)

    async def _read_loop(self, reader: asyncio.StreamReader, writer: asyncio.StreamWriter):
        """数据读取循环"""
        flush_task = asyncio.create_task(self._periodic_flush())
        total_bytes = 0

        try:
            while self.running:
                data = await reader.read(65536)
                if not data:
                    logging.warning("服务器断开连接")
                    break

                total_bytes += len(data)
                logging.debug("收到 %d 字节 (累计 %d)" % (len(data), total_bytes))

                # 打印前64字节用于调试
                if self.stats["frames"] < 5:
                    logging.info("数据样例: %s" % data[:64].hex())

                # 解析帧
                self.parser.feed(data)
                frames = self.parser.parse_frames()

                if frames:
                    logging.info("解析到 %d 帧" % len(frames))

                for frame in frames:
                    await self._process_frame(frame)

        finally:
            flush_task.cancel()

    async def _process_frame(self, frame: dict):
        """处理单帧"""
        self.stats["frames"] += 1

        # 调试：打印帧内容（仅前10帧）
        if self.stats["frames"] <= 10:
            json_data = frame.get("json")
            itb3 = frame.get("itb3")
            tb1 = frame.get("tb1")
            tb3 = frame.get("tb3")
            raw_len = len(frame.get("_raw", b""))
            logging.info("帧[%d] raw=%d json=%s itb3=%s tb1=%s tb3=%s" % (
                self.stats["frames"],
                raw_len,
                "YES" if json_data else "no",
                "YES(%d)" % len(itb3) if itb3 else "no",
                "YES(%d)" % len(tb1) if tb1 else "no",
                "YES(%d)" % len(tb3) if tb3 else "no",
            ))
            if json_data:
                logging.info("  JSON: %s" % json.dumps(json_data, ensure_ascii=False)[:300])

        # 提取股票代码
        stock_code = self.extractor.extract_stock_code(frame)

        # 提取逐笔数据 (JiTu)
        ticks = self.extractor.extract_ticks(frame)
        for tick in ticks:
            code = tick.stock_code or stock_code or "unknown"
            await self.writer.add_tick(code, tick)
            self.stats["ticks"] += 1

        # 提取盘口数据 (itb3.0)
        quote = self.extractor.extract_quote(frame)
        if quote:
            code = stock_code or "unknown"
            await self.writer.add_quote(code, quote)
            self.stats["quotes"] += 1

        # 提取 tb1.0 数据（逐笔明细/价格数据）
        tb10 = self.extractor.parse_tb10(frame)
        if tb10:
            code = tb10["stock_code"] or stock_code or "unknown"
            await self.writer.add_tb10(code, tb10)
            self.stats["tb10"] += 1
            if self.stats["frames"] <= 10:
                logging.info("  tb10: %s prices=%s" % (code, tb10["prices"]))

        # 定期日志
        if self.stats["frames"] % 100 == 0:
            self._log_stats()

    async def _periodic_flush(self):
        """定期刷新批量队列"""
        while self.running:
            await asyncio.sleep(self.config.FLUSH_INTERVAL_MS / 1000)
            await self.writer.flush_all()

    def _log_stats(self):
        """打印统计"""
        elapsed = int(time.time()) - self.stats["start_time"]
        fps = self.stats["frames"] / elapsed if elapsed > 0 else 0
        logging.info(
            "统计: %d 帧, %d ticks, %d quotes, %d tb10, %d 错误, %.1f 帧/秒"
            % (self.stats["frames"], self.stats["ticks"],
               self.stats["quotes"], self.stats["tb10"],
               self.stats["errors"], fps)
        )

    async def stop(self):
        """停止引擎"""
        self.running = False
        if self.writer:
            await self.writer.flush_all()
        logging.info("引擎已停止")


# ==================== 入口 ====================

async def main():
    logging.basicConfig(
        level=logging.INFO,
        format="%(asctime)s [%(levelname)s] %(message)s",
        datefmt="%H:%M:%S",
    )

    config = Config()
    engine = THSL2Engine(config)

    try:
        await engine.start()
    except KeyboardInterrupt:
        await engine.stop()


if __name__ == "__main__":
    asyncio.run(main())
