"""
同花顺流量专用捕获插件
规则：只拦截同花顺相关流量，其余全部透传不做任何修改

使用方法：
    mitmweb -p 8080 -s ths_capture.py --web-host 127.0.0.1 --web-port 8081

依赖：
    pip install mitmproxy
"""

import json
import re
from mitmproxy import http, ctx

# 同花顺相关域名
THS_DOMAINS = [
    "10jqka.com.cn",
    "hexin.cn",
    "hexin.com",
    "ths.com.cn",
    "ironmind.cn",
    "pass.ttyhuo.cn",
    "10jqka",
    "hexin",
]

# L2 行情关键路径（v10.01.02 走 TCP，但保留检测）
L2_PATH_PATTERNS = [
    "/fuyao/common_hq_aggr_cache/quote/v1/multi_last_snapshot",
    "/fuyao/common_hq_aggr_cache/quote/v1/single_trend",
    "/fuyao/common_hq_aggr_cache/quote/v1/trade_time",
    "/fuyao/common_hq_aggr_cache/quote/v1/depth",
    "/fuyao/common_hq_aggr_cache/quote/v1/tick",
    "/fuyao/common_hq_aggr_cache/quote/v1/order_queue",
]


def is_ths_traffic(host: str, path: str) -> bool:
    """判断是否为同花顺流量"""
    host_lower = host.lower()
    for domain in THS_DOMAINS:
        if domain in host_lower:
            return True
    return False


def is_l2_data(path: str) -> bool:
    """判断是否为 L2 行情数据路径"""
    for pattern in L2_PATH_PATTERNS:
        if pattern in path:
            return True
    return False


def try_parse_json(body: bytes) -> tuple:
    """尝试解析 JSON，返回 (is_json, parsed_data)"""
    try:
        text = body.decode("utf-8")
        data = json.loads(text)
        return True, data
    except (UnicodeDecodeError, json.JSONDecodeError):
        return False, None


def try_parse_protobuf(body: bytes) -> bool:
    """简单判断是否为 Protobuf（检查是否为二进制且非标准格式）"""
    if len(body) < 4:
        return False
    printable = sum(1 for b in body[:100] if 32 <= b <= 126 or b in (10, 13, 9))
    ratio = printable / min(len(body), 100)
    return ratio < 0.5 and len(body) > 20


def format_json(data, indent=2) -> str:
    """美化 JSON 输出"""
    return json.dumps(data, ensure_ascii=False, indent=indent)


class THSCapture:
    def __init__(self):
        self.request_count = 0
        self.ths_count = 0

    def request(self, flow: http.HTTPFlow) -> None:
        """处理请求 - 只记录同花顺流量，不做修改"""
        host = flow.request.pretty_host
        path = flow.request.path
        self.request_count += 1

        if not is_ths_traffic(host, path):
            return  # 非同花顺流量：完全放行

        self.ths_count += 1
        l2_flag = "🎯 L2" if is_l2_data(path) else ""

        ctx.log.info(f"\n{'='*60}")
        ctx.log.info(f"📤 同花顺请求 #{self.ths_count} {l2_flag}")
        ctx.log.info(f"{'='*60}")
        ctx.log.info(f"  方法: {flow.request.method}")
        ctx.log.info(f"  URL:  {flow.request.pretty_url}")
        ctx.log.info(f"  Host: {host}")
        ctx.log.info(f"  Path: {path}")
        
        ctx.log.info(f"  --- 请求头 ---")
        for key, value in flow.request.headers.items():
            ctx.log.info(f"    {key}: {value}")

        if flow.request.content:
            content_type = flow.request.headers.get("Content-Type", "")
            ctx.log.info(f"  --- 请求体 ({len(flow.request.content)} bytes, {content_type}) ---")
            if "json" in content_type.lower():
                try:
                    data = json.loads(flow.request.content.decode("utf-8"))
                    ctx.log.info(format_json(data))
                except:
                    ctx.log.info(flow.request.content[:500].decode("utf-8", errors="replace"))
            else:
                preview = flow.request.content[:300].decode("utf-8", errors="replace")
                ctx.log.info(f"    {preview}{'...' if len(flow.request.content) > 300 else ''}")

    def response(self, flow: http.HTTPFlow) -> None:
        """处理响应 - 只解析同花顺流量，不做修改"""
        host = flow.request.pretty_host
        path = flow.request.path

        if not is_ths_traffic(host, path):
            return  # 非同花顺流量：完全放行

        l2_flag = "🎯 L2" if is_l2_data(path) else ""

        ctx.log.info(f"\n{'='*60}")
        ctx.log.info(f"📥 同花顺响应 #{self.ths_count} {l2_flag}")
        ctx.log.info(f"{'='*60}")
        ctx.log.info(f"  URL:  {flow.request.pretty_url}")
        ctx.log.info(f"  状态: {flow.response.status_code}")
        ctx.log.info(f"  --- 响应头 ---")
        for key, value in flow.response.headers.items():
            ctx.log.info(f"    {key}: {value}")

        if flow.response.content:
            body = flow.response.content
            content_type = flow.response.headers.get("Content-Type", "")
            ctx.log.info(f"  --- 响应体 ({len(body)} bytes, {content_type}) ---")

            is_json, json_data = try_parse_json(body)
            if is_json:
                ctx.log.info("  [JSON 格式]")
                formatted = format_json(json_data)
                if len(formatted) > 3000:
                    ctx.log.info(formatted[:3000] + "\n    ... (截断)")
                else:
                    ctx.log.info(formatted)
                
                body_str = body.decode("utf-8")
                if any(k in body_str for k in ["bid", "ask", "sell", "buy"]):
                    ctx.log.warn("  ⚡ 检测到买卖盘相关字段！")
                if any(k in body_str for k in ["tender", "detail", "trade_time", "成交"]):
                    ctx.log.warn("  ⚡ 检测到逐笔成交相关字段！")
                if any(k in body_str for k in ["depth", "queue", "order"]):
                    ctx.log.warn("  ⚡ 检测到委托队列相关字段！")
                    
            elif "xml" in content_type.lower() or body[:5] in (b"<?xml", b"<xml>"):
                ctx.log.info("  [XML 格式]")
                ctx.log.info(body.decode("utf-8", errors="replace")[:2000])
            elif try_parse_protobuf(body):
                ctx.log.info("  [Protobuf/二进制格式]")
                ctx.log.info(f"    Hex 前100字节: {body[:100].hex()}")
                stock_codes = re.findall(b'(sh|sz)(\d{6})', body)
                if stock_codes:
                    ctx.log.warn(f"  ⚡ 发现股票代码: {[f'{p.decode()}{n.decode()}' for p, n in stock_codes[:10]]}")
            else:
                ctx.log.info("  [未知/文本格式]")
                ctx.log.info(body[:500].decode("utf-8", errors="replace"))


addons = [THSCapture()]
