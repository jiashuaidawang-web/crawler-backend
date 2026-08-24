#!/usr/bin/env python3
"""
同花顺 L2 逐笔成交解码器 (tb1.0 结构化记录解析)
================================================

破局思路:
  tb1.0 header 中有 count + struct_size 字段
  data area = count 条定长记录, 每条 struct_size 字节
  每条记录包含: time, price, volume, direction (编码方式待定)

  通过多种字段布局尝试 + 启发式评分, 自动选择最优解析方案

核心创新:
  1. 按 struct_size 切分 data area (现有代码只扫描 UTF-16LE 字符串, 遗漏二进制)
  2. 多策略字段解析 (尝试不同的 field order / size / encoding)
  3. 启发式评分 (价格范围、成交量合理性、时间方向、价格波动率)
  4. 与 JiTu 交叉验证 (JiTu 提供 direction + time, tb1.0 提供 price + volume)

使用:
  from ths_l2_tick_decoder import TB10RecordParser, TickRecord
  parser = TB10RecordParser()
  header = parser.parse_header(tb1_data)
  records = parser.parse_data_area(data_area, header)
"""

import struct
import logging
from typing import List, Optional, Tuple, Dict, Callable
from dataclasses import dataclass, field

logger = logging.getLogger(__name__)


# ==================== 数据模型 ====================

@dataclass
class TB10Header:
    """tb1.0 头部 (40 bytes)"""
    marker: str           # "tb1.0" 或 "ltb1.0"
    count: int            # 记录数
    struct_size: int      # 每条记录的字节数
    market: int           # 市场标识 (11=上海, 33=深圳)
    flags: int
    timestamp1: int       # 帧时间戳1
    timestamp2: int       # 帧时间戳2
    header_size: int      # 头部实际字节数 (40 或 44)
    raw_header: bytes     # 原始头部字节

    def __repr__(self):
        return (f"TB10Header(count={self.count}, struct_size={self.struct_size}, "
                f"market={self.market}, ts1={self.timestamp1}, ts2={self.timestamp2})")


@dataclass
class TickRecord:
    """解析后的单条成交记录"""
    time_ms: int          # 时间 (毫秒, 从0点起算)
    price: float          # 成交价
    volume: int           # 成交量 (手)
    direction: int        # 方向: 0=买, 1=卖
    raw_bytes: bytes      # 原始记录字节
    strategy: str         # 使用的解析策略名称
    score: float          # 解析置信度 (0-1)

    @property
    def direction_str(self) -> str:
        return "B" if self.direction == 0 else "S"

    @property
    def time_str(self) -> str:
        """返回 HH:MM:SS.mmm 格式"""
        hours = self.time_ms // 3600000
        minutes = (self.time_ms % 3600000) // 60000
        seconds = (self.time_ms % 60000) // 1000
        ms = self.time_ms % 1000
        return f"{hours:02d}:{minutes:02d}:{seconds:02d}.{ms:03d}"

    def to_dict(self) -> dict:
        return {
            "time": self.time_str,
            "time_ms": self.time_ms,
            "price": self.price,
            "volume": self.volume,
            "direction": self.direction_str,
            "score": round(self.score, 3),
            "strategy": self.strategy,
        }

    def __repr__(self):
        return (f"Tick({self.time_str} {self.direction_str} "
                f"¥{self.price:.2f} {self.volume}手 "
                f"[{self.strategy}|{self.score:.2f}])")


# ==================== 核心解析器 ====================

class TB10RecordParser:
    """
    tb1.0 结构化记录解析器

    支持多种字段布局策略, 自动选择最优方案
    """

    # 价格编码的候选除数 (价格 = raw / divisor)
    PRICE_DIVISORS = [1, 10, 100, 1000, 10000]

    # 合理的取值范围
    PRICE_MIN = 0.01
    PRICE_MAX = 100000.0
    VOLUME_MIN = 1
    VOLUME_MAX = 10000000  # 1000万手
    TIME_MIN = 0
    TIME_MAX = 86400000    # 24小时对应的毫秒数

    # 交易时段 (毫秒 from 0:00)
    TRADE_START_AM = 9 * 3600000 + 30 * 60000   # 9:30
    TRADE_END_AM = 11 * 3600000 + 30 * 60000    # 11:30
    TRADE_START_PM = 13 * 3600000               # 13:00
    TRADE_END_PM = 15 * 3600000                 # 15:00

    def __init__(self):
        self._last_good_strategy = None
        self._stats = {"parsed": 0, "failed": 0, "strategy_counts": {}}

    # ---------- 头部解析 ----------

    def parse_header(self, data: bytes) -> Optional[TB10Header]:
        """
        解析 tb1.0 头部

        支持两种格式:
        1. 直接从 "tb1.0\x00" 开始 (40 bytes header)
        2. 前面有 4 字节前缀 (44 bytes total)
        """
        if len(data) < 40:
            return None

        # 尝试直接从偏移 0 开始
        if data[0:6] in (b'tb1.0\x00', b'ltb1.0\x00'):
            return self._parse_header_at(data, 0, 40)

        # 尝试从偏移 4 开始 (前面有 4 字节前缀)
        if len(data) >= 44 and data[4:10] in (b'tb1.0\x00', b'ltb1.0\x00'):
            return self._parse_header_at(data, 4, 44)

        # 尝试从偏移 2 开始
        if len(data) >= 42 and data[2:8] in (b'tb1.0\x00', b'ltb1.0\x00'):
            return self._parse_header_at(data, 2, 42)

        return None

    def _parse_header_at(self, data: bytes, offset: int, header_size: int) -> Optional[TB10Header]:
        """从指定偏移解析头部"""
        try:
            p = offset
            marker_raw = data[p:p+6]
            marker = marker_raw.decode('ascii', errors='replace').strip('\x00')
            p += 6

            count = struct.unpack_from('<I', data, p)[0]; p += 4
            reserved1 = struct.unpack_from('<I', data, p)[0]; p += 4
            reserved2 = struct.unpack_from('<I', data, p)[0]; p += 4
            struct_size = struct.unpack_from('<H', data, p)[0]; p += 2
            market = struct.unpack_from('<H', data, p)[0]; p += 2
            flags = struct.unpack_from('<I', data, p)[0]; p += 4
            unknown1 = struct.unpack_from('<I', data, p)[0]; p += 4
            timestamp1 = struct.unpack_from('<I', data, p)[0]; p += 4
            unknown2 = struct.unpack_from('<H', data, p)[0]; p += 2
            timestamp2 = struct.unpack_from('<I', data, p)[0]; p += 4

            return TB10Header(
                marker=marker,
                count=count,
                struct_size=struct_size,
                market=market,
                flags=flags,
                timestamp1=timestamp1,
                timestamp2=timestamp2,
                header_size=header_size,
                raw_header=data[offset:p],
            )
        except struct.error:
            return None

    # ---------- Data Area 解析 ----------

    def parse_data_area(self, data_area: bytes, header: TB10Header) -> List[TickRecord]:
        """
        解析 data area 为记录列表

        策略:
        1. 找到记录起始位置 (跳过前面的 UTF-16LE 字符串 metadata)
        2. 按 struct_size 切分记录
        3. 对每条记录尝试多种字段布局
        4. 选择评分最高的方案
        """
        records = []

        if header.struct_size <= 0 or header.count <= 0:
            return records
        if header.struct_size > 256 or header.count > 10000:
            # 异常值, 可能是解析错误
            logger.warning("异常 header: struct_size=%d, count=%d", header.struct_size, header.count)
            return records

        # 找到记录起始位置
        record_start = self._find_record_start(data_area, header)

        # 计算可用记录数
        available_bytes = len(data_area) - record_start
        if available_bytes < header.struct_size:
            return records
        max_records = available_bytes // header.struct_size
        parse_count = min(header.count, max_records)

        if parse_count <= 0:
            return records

        # 尝试不同的字段布局策略
        best_records = []
        best_score = -1.0
        best_strategy = ""

        strategies = self._get_strategies()

        for strategy_name, strategy_fn in strategies:
            candidate_records = []
            valid_count = 0

            for i in range(parse_count):
                offset = record_start + i * header.struct_size
                record_bytes = data_area[offset:offset + header.struct_size]

                record = strategy_fn(record_bytes, header)
                if record is not None:
                    candidate_records.append(record)
                    valid_count += 1

            # 评分
            if candidate_records:
                quality_score = self._score_records(candidate_records)
                completeness = valid_count / parse_count
                total_score = quality_score * completeness

                if total_score > best_score:
                    best_score = total_score
                    best_records = candidate_records
                    best_strategy = strategy_name

        # 更新统计
        self._stats["parsed"] += 1
        if best_records:
            self._stats["strategy_counts"][best_strategy] = \
                self._stats["strategy_counts"].get(best_strategy, 0) + 1
            self._last_good_strategy = best_strategy
        else:
            self._stats["failed"] += 1

        return best_records

    def _find_record_start(self, data_area: bytes, header: TB10Header) -> int:
        """
        找到记录区域的起始偏移

        data area 可能以 UTF-16LE 字符串开头(股票代码等 metadata),
        需要跳过这些字符串找到二进制记录区域
        """
        if len(data_area) < 2:
            return 0

        # 检查是否是 UTF-16LE 字符串开头 (低字节是 ASCII, 高字节是 0x00)
        if data_area[1] == 0 and 0x20 <= data_area[0] < 0x7f:
            # 跳过 UTF-16LE 字符串区域
            i = 0
            while i < len(data_area) - 1:
                if data_area[i+1] == 0 and 0x20 <= data_area[i] < 0x7f:
                    i += 2
                else:
                    break
            # 对齐到 4 字节边界 (结构体通常 4 字节对齐)
            aligned = (i + 3) & ~3
            return min(aligned, len(data_area))

        return 0

    # ---------- 字段布局策略 ----------

    def _get_strategies(self) -> List[Tuple[str, Callable]]:
        """
        返回所有字段布局策略

        策略命名: "布局: 字段顺序和大小"
        字段缩写: t=time, p=price, v=volume, d=direction
        数字=字节数
        """
        return [
            ("A: t4+p4+v4+d1", self._strategy_A),
            ("B: t4+p4+v2+d1", self._strategy_B),
            ("C: t2+p4+v4+d1", self._strategy_C),
            ("D: p4+t4+v4+d1", self._strategy_D),
            ("E: t4+p4+v4+d1(be)", self._strategy_E),
            ("F: t2+p4+v2+d1+pad", self._strategy_F),
            ("G: t4+p2+v4+d1", self._strategy_G),
            ("H: t4+p4+v4+d1+amt4", self._strategy_H),
        ]

    def _strategy_A(self, data: bytes, header: TB10Header) -> Optional[TickRecord]:
        """
        布局 A: time(4) + price(4) + volume(4) + direction(1) + padding
        偏移: 0-3=time, 4-7=price, 8-11=volume, 12=direction
        """
        if len(data) < 13:
            return None

        time_raw = struct.unpack_from('<I', data, 0)[0]
        price_raw = struct.unpack_from('<I', data, 4)[0]
        vol_raw = struct.unpack_from('<I', data, 8)[0]
        direction = data[12]

        return self._build_record(data, time_raw, price_raw, vol_raw, direction, "A")

    def _strategy_B(self, data: bytes, header: TB10Header) -> Optional[TickRecord]:
        """
        布局 B: time(4) + price(4) + volume(2) + direction(1) + padding
        偏移: 0-3=time, 4-7=price, 8-9=volume, 10=direction
        """
        if len(data) < 11:
            return None

        time_raw = struct.unpack_from('<I', data, 0)[0]
        price_raw = struct.unpack_from('<I', data, 4)[0]
        vol_raw = struct.unpack_from('<H', data, 8)[0]
        direction = data[10]

        return self._build_record(data, time_raw, price_raw, vol_raw, direction, "B")

    def _strategy_C(self, data: bytes, header: TB10Header) -> Optional[TickRecord]:
        """
        布局 C: time(2) + price(4) + volume(4) + direction(1) + padding
        偏移: 0-1=time, 2-5=price, 6-9=volume, 10=direction
        """
        if len(data) < 11:
            return None

        time_raw = struct.unpack_from('<H', data, 0)[0]
        price_raw = struct.unpack_from('<I', data, 2)[0]
        vol_raw = struct.unpack_from('<I', data, 6)[0]
        direction = data[10]

        return self._build_record(data, time_raw, price_raw, vol_raw, direction, "C")

    def _strategy_D(self, data: bytes, header: TB10Header) -> Optional[TickRecord]:
        """
        布局 D: price(4) + time(4) + volume(4) + direction(1) + padding
        偏移: 0-3=price, 4-7=time, 8-11=volume, 12=direction
        """
        if len(data) < 13:
            return None

        price_raw = struct.unpack_from('<I', data, 0)[0]
        time_raw = struct.unpack_from('<I', data, 4)[0]
        vol_raw = struct.unpack_from('<I', data, 8)[0]
        direction = data[12]

        return self._build_record(data, time_raw, price_raw, vol_raw, direction, "D")

    def _strategy_E(self, data: bytes, header: TB10Header) -> Optional[TickRecord]:
        """
        布局 E: time(4) + price(4) + volume(4) + direction(1) (大端序)
        偏移: 0-3=time, 4-7=price, 8-11=volume, 12=direction
        """
        if len(data) < 13:
            return None

        time_raw = struct.unpack_from('>I', data, 0)[0]
        price_raw = struct.unpack_from('>I', data, 4)[0]
        vol_raw = struct.unpack_from('>I', data, 8)[0]
        direction = data[12]

        return self._build_record(data, time_raw, price_raw, vol_raw, direction, "E")

    def _strategy_F(self, data: bytes, header: TB10Header) -> Optional[TickRecord]:
        """
        布局 F: time(2) + price(4) + volume(2) + direction(1) + padding(1)
        偏移: 0-1=time, 2-5=price, 6-7=volume, 8=direction, 9=padding
        """
        if len(data) < 10:
            return None

        time_raw = struct.unpack_from('<H', data, 0)[0]
        price_raw = struct.unpack_from('<I', data, 2)[0]
        vol_raw = struct.unpack_from('<H', data, 6)[0]
        direction = data[8]

        return self._build_record(data, time_raw, price_raw, vol_raw, direction, "F")

    def _strategy_G(self, data: bytes, header: TB10Header) -> Optional[TickRecord]:
        """
        布局 G: time(4) + price(2) + volume(4) + direction(1) + padding
        偏移: 0-3=time, 4-5=price, 6-9=volume, 10=direction
        适用于低价股 (price < 655.35)
        """
        if len(data) < 11:
            return None

        time_raw = struct.unpack_from('<I', data, 0)[0]
        price_raw = struct.unpack_from('<H', data, 4)[0]
        vol_raw = struct.unpack_from('<I', data, 6)[0]
        direction = data[10]

        return self._build_record(data, time_raw, price_raw, vol_raw, direction, "G")

    def _strategy_H(self, data: bytes, header: TB10Header) -> Optional[TickRecord]:
        """
        布局 H: time(4) + price(4) + volume(4) + direction(1) + amount(4) + padding
        偏移: 0-3=time, 4-7=price, 8-11=volume, 12=direction, 13-16=amount
        """
        if len(data) < 17:
            return None

        time_raw = struct.unpack_from('<I', data, 0)[0]
        price_raw = struct.unpack_from('<I', data, 4)[0]
        vol_raw = struct.unpack_from('<I', data, 8)[0]
        direction = data[12]
        # amount = struct.unpack_from('<I', data, 13)[0]  # 暂不使用

        return self._build_record(data, time_raw, price_raw, vol_raw, direction, "H")

    # ---------- 字段解码 ----------

    def _build_record(self, raw: bytes, time_raw: int, price_raw: int,
                      vol_raw: int, direction: int, strategy: str) -> Optional[TickRecord]:
        """尝试从原始字段值构建 TickRecord"""
        # 解码价格
        price, price_score = self._decode_price(price_raw)
        if price is None:
            return None

        # 解码时间
        time_ms, time_score = self._decode_time(time_raw)
        if time_ms is None:
            return None

        # 解码成交量
        vol, vol_score = self._decode_volume(vol_raw)
        if vol is None:
            return None

        # 方向
        if direction in (0, 1, 0x42, 0x53, 0x62, 0x73):  # 0,1,B,S,b,s
            dir_val = 0 if direction in (0, 0x42, 0x62) else 1
            dir_score = 1.0
        elif direction < 10:
            dir_val = direction % 2
            dir_score = 0.5
        else:
            dir_val = 0
            dir_score = 0.1

        total_score = (price_score + time_score + vol_score + dir_score) / 4

        return TickRecord(
            time_ms=time_ms,
            price=price,
            volume=vol,
            direction=dir_val,
            raw_bytes=raw,
            strategy=strategy,
            score=total_score,
        )

    def _decode_price(self, raw: int) -> Tuple[Optional[float], float]:
        """
        尝试解码价格

        返回 (price, confidence)
        尝试不同的除数, 选择在合理范围内的最佳结果
        """
        if raw == 0:
            return None, 0.0

        best_price = None
        best_score = 0.0

        for div in self.PRICE_DIVISORS:
            price = raw / div
            if self.PRICE_MIN <= price <= self.PRICE_MAX:
                # 评分: 越常见的价格范围分数越高
                if 0.5 <= price <= 500:
                    score = 1.0
                elif 0.01 <= price <= 2000:
                    score = 0.8
                elif 2000 < price <= 10000:
                    score = 0.6
                else:
                    score = 0.3

                # 偏好除数 100 (最常见的股票价格精度)
                if div == 100:
                    score *= 1.1
                elif div == 1000:
                    score *= 1.05

                if score > best_score:
                    best_score = score
                    best_price = price

        return best_price, best_score

    def _decode_time(self, raw: int) -> Tuple[Optional[int], float]:
        """
        尝试解码时间

        返回 (time_ms, confidence)
        可能是毫秒或秒
        """
        # 直接作为毫秒
        if self.TIME_MIN <= raw <= self.TIME_MAX:
            score = self._time_score(raw)
            return raw, score

        # 作为秒 (乘以 1000)
        time_ms = raw * 1000
        if self.TIME_MIN <= time_ms <= self.TIME_MAX:
            score = self._time_score(time_ms) * 0.85  # 略降权
            return time_ms, score

        # 作为 0.1 秒 (乘以 100)
        time_ms = raw * 100
        if self.TIME_MIN <= time_ms <= self.TIME_MAX:
            score = self._time_score(time_ms) * 0.7
            return time_ms, score

        return None, 0.0

    def _time_score(self, time_ms: int) -> float:
        """根据时间是否在交易时段评分"""
        # 上午交易时段
        if self.TRADE_START_AM <= time_ms <= self.TRADE_END_AM:
            return 1.0
        # 下午交易时段
        if self.TRADE_START_PM <= time_ms <= self.TRADE_END_PM:
            return 1.0
        # 集合竞价时段 (9:15-9:25)
        if 9 * 3600000 + 15 * 60000 <= time_ms <= 9 * 3600000 + 25 * 60000:
            return 0.8
        # 其他时间
        return 0.3

    def _decode_volume(self, raw: int) -> Tuple[Optional[int], float]:
        """
        尝试解码成交量

        返回 (volume, confidence)
        """
        if raw == 0:
            return None, 0.0

        if self.VOLUME_MIN <= raw <= self.VOLUME_MAX:
            if 1 <= raw <= 100000:
                return raw, 1.0
            elif raw <= 1000000:
                return raw, 0.7
            else:
                return raw, 0.3

        return None, 0.0

    # ---------- 评分 ----------

    def _score_records(self, records: List[TickRecord]) -> float:
        """对一组记录评分"""
        if not records:
            return 0.0

        # 平均置信度
        scores = [r.score for r in records]
        avg_score = sum(scores) / len(scores)

        # 价格变化合理性
        if len(records) > 1:
            prices = [r.price for r in records]
            price_min = min(prices)
            price_max = max(prices)
            avg_price = sum(prices) / len(prices)

            if avg_price > 0:
                volatility = (price_max - price_min) / avg_price
                # 正常逐笔成交的价格变化应该很小 (<2%)
                if volatility < 0.02:
                    avg_score *= 1.3
                elif volatility < 0.05:
                    avg_score *= 1.1
                elif volatility < 0.1:
                    avg_score *= 1.0
                elif volatility > 0.5:
                    avg_score *= 0.5

        # 成交量合理性
        volumes = [r.volume for r in records]
        avg_vol = sum(volumes) / len(volumes)
        if 1 <= avg_vol <= 10000:
            avg_score *= 1.1
        elif avg_vol > 100000:
            avg_score *= 0.8

        return min(avg_score, 1.0)

    # ---------- 统计 ----------

    def get_stats(self) -> dict:
        """获取解析统计"""
        return dict(self._stats)

    def get_best_strategy(self) -> Optional[str]:
        """返回最近使用的最佳策略"""
        return self._last_good_strategy


# ==================== JiTu + tb1.0 关联器 ====================

class TickCorrelator:
    """
    将 JiTu (方向+时间) 与 tb1.0 记录 (价格+成交量) 关联

    策略:
    1. 如果 tb1.0 解析成功 (有 price + volume), 直接使用
    2. 如果 tb1.0 解析失败, 用 JiTu 提供 direction + time, 价格从最近的 tb1.0 快照推断
    3. 两者时间戳匹配时, 合并信息
    """

    def __init__(self):
        self._last_price: Dict[str, float] = {}  # code -> last known price
        self._last_quote: Dict[str, dict] = {}   # code -> last quote snapshot

    def update_quote(self, code: str, quote: dict):
        """更新最新的价格快照"""
        self._last_quote[code] = quote
        if "last_price" in quote:
            self._last_price[code] = quote["last_price"]

    def correlate(self, jitu_ticks: List[dict], tb10_records: List[TickRecord],
                  stock_code: str) -> List[dict]:
        """
        关联 JiTu 和 tb1.0 数据

        返回完整的 tick 列表, 每条包含: time, price, direction, volume
        """
        result = []

        # 优先使用 tb10 记录 (包含完整信息)
        if tb10_records:
            for rec in tb10_records:
                result.append({
                    "time": rec.time_str,
                    "time_ms": rec.time_ms,
                    "price": rec.price,
                    "volume": rec.volume,
                    "direction": rec.direction_str,
                    "source": "tb10",
                    "score": rec.score,
                })
            return result

        # 回退: 使用 JiTu + 最近价格
        last_price = self._last_price.get(stock_code, 0.0)
        for tick in jitu_ticks:
            time_ms = tick.get("time", 0)
            if isinstance(time_ms, int) and time_ms < 86400:
                time_ms = time_ms * 1000  # 秒 -> 毫秒

            hours = time_ms // 3600000
            minutes = (time_ms % 3600000) // 60000
            seconds = (time_ms % 60000) // 1000
            ms = time_ms % 1000

            result.append({
                "time": f"{hours:02d}:{minutes:02d}:{seconds:02d}.{ms:03d}",
                "time_ms": time_ms,
                "price": last_price,  # 使用最近已知价格
                "volume": 0,  # JiTu 没有成交量
                "direction": tick.get("direction", "U"),
                "source": "jitu_fallback",
                "score": 0.3,
            })

        return result
