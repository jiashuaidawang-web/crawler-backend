#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
同花顺资金流向 API 验证脚本 v2
使用 py_mini_racer + ths.js 计算 hexin-v token（不需要浏览器）
"""
import io
import json
import sys
import time
import hashlib
import random
import re
import requests
from datetime import datetime

sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding="utf-8", errors="replace")
sys.stderr = io.TextIOWrapper(sys.stderr.buffer, encoding="utf-8", errors="replace")

# ============================================================
# 方案A: py_mini_racer + ths.js
# ============================================================
def get_token_mini_racer():
    """用 py_mini_racer 执行 ths.js 计算 token"""
    try:
        from py_mini_racer import MiniRacer
    except ImportError:
        print("[token] py_mini_racer 未安装，尝试安装...")
        import subprocess
        subprocess.run([sys.executable, "-m", "pip", "install", "py_mini_racer", "-q"])
        from py_mini_racer import MiniRacer

    # 读取 ths.js
    ths_js_path = "hexin-v-extractor/chameleon.1.13.min.js"
    try:
        with open(ths_js_path, "r", encoding="utf-8") as f:
            js_code = f.read()
    except FileNotFoundError:
        print(f"[token] 找不到 {ths_js_path}")
        return None

    # 初始化 JS 环境
    ctx = MiniRacer()

    # 注入必要的浏览器环境 mock
    mock_env = """
    var window = {};
    var document = {
        createElement: function(tag) {
            return {
                style: {},
                setAttribute: function() {},
                appendChild: function() {},
                addEventListener: function() {},
                getContext: function() { return null; }
            };
        },
        addEventListener: function() {},
        documentElement: { style: {} },
        body: { appendChild: function() {}, removeChild: function() {} },
        cookie: '',
        referrer: 'https://data.10jqka.com.cn/',
        location: { href: 'https://data.10jqka.com.cn/', hostname: 'data.10jqka.com.cn' }
    };
    var navigator = {
        userAgent: 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36',
        platform: 'Win32',
        language: 'zh-CN',
        languages: ['zh-CN', 'zh'],
        cookieEnabled: true,
        plugins: [],
        mimeTypes: []
    };
    var screen = { width: 1920, height: 1080, colorDepth: 24 };
    var location = { href: 'https://data.10jqka.com.cn/', hostname: 'data.10jqka.com.cn' };
    var setTimeout = function(){};
    var setInterval = function(){};
    var Image = function(){};
    var AudioContext = function(){};
    var HTMLElement = function(){};
    var CanvasRenderingContext2D = function(){};
    var Performance = function(){ now: function(){ return Date.now(); } };
    """

    try:
        ctx.eval(mock_env)
        ctx.eval(js_code)
        # 调用 v() 函数获取 token
        token = ctx.call("v")
        print(f"[token] py_mini_racer 获取成功: {str(token)[:40]}...")
        return str(token)
    except Exception as e:
        print(f"[token] py_mini_racer 执行失败: {e}")
        return None


# ============================================================
# 方案B: 纯 Python 模拟（简化版，可能不通过验证）
# ============================================================
def get_token_simple():
    """生成一个简单的 token（可能不通过服务端验证，仅用于测试接口是否通）"""
    # 同花顺 token 看起来是 base64 编码的
    import base64
    # 随机生成一个类似格式的
    rand_bytes = bytes([random.randint(0, 255) for _ in range(21)])
    token = base64.b64encode(rand_bytes).decode()
    print(f"[token] 生成随机 token (可能无效): {token[:40]}...")
    return token


# ============================================================
# 通用请求
# ============================================================
def make_headers(token, referer="https://data.10jqka.com.cn/"):
    return {
        "Accept": "text/html, */*; q=0.01",
        "Accept-Encoding": "gzip, deflate",
        "Accept-Language": "zh-CN,zh;q=0.9,en;q=0.8",
        "Cache-Control": "no-cache",
        "Connection": "keep-alive",
        "hexin-v": token,
        "Host": "data.10jqka.com.cn",
        "Pragma": "no-cache",
        "Referer": referer,
        "User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36",
        "X-Requested-With": "XMLHttpRequest",
    }


def section(t):
    print(f"\n{'='*70}\n {t}\n{'='*70}")


def test_all_apis(token):
    """测试所有 API 接口"""

    # ============================================================
    section("1. 北向资金")
    # ============================================================
    apis = [
        ("北向资金-沪深港通", "https://data.10jqka.com.cn/hsgt/api/zjlx/",
         "https://data.10jqka.com.cn/hsgt/"),
        ("北向资金-个股排行", "https://data.10jqka.com.cn/hsgt/api/zjlx/boardType/5/page/1/ajax/1/",
         "https://data.10jqka.com.cn/hsgt/"),
        ("北向资金-板块Type6", "https://data.10jqka.com.cn/hsgt/api/zjlx/boardType/6/",
         "https://data.10jqka.com.cn/hsgt/"),
        ("北向资金-板块Type8", "https://data.10jqka.com.cn/hsgt/api/zjlx/boardType/8/",
         "https://data.10jqka.com.cn/hsgt/"),
    ]

    for name, url, ref in apis:
        print(f"\n  [{name}]")
        print(f"  URL: {url}")
        try:
            resp = requests.get(url, headers=make_headers(token, ref), timeout=15)
            print(f"  Status: {resp.status_code}, Length: {len(resp.text)}")
            if resp.status_code == 200 and len(resp.text) > 50:
                text = resp.text
                if text.strip().startswith(("{", "[")):
                    data = json.loads(text)
                    arr_info = f"array[{len(data)}]" if isinstance(data, list) else ""
                    print(f"  JSON keys: {list(data.keys()) if isinstance(data, dict) else arr_info}")
                    print(f"  Preview: {json.dumps(data, ensure_ascii=False)[:400]}...")
                else:
                    rows = re.findall(r'<tr[^>]*>(.*?)</tr>', text, re.DOTALL)
                    print(f"  HTML rows: {len(rows)}")
                    print(f"  Preview: {text[:300]}...")
            else:
                print(f"  Response: {resp.text[:200]}")
        except Exception as e:
            print(f"  Error: {e}")
        time.sleep(0.3)

    # ============================================================
    section("2. 板块资金流向")
    # ============================================================
    apis = [
        ("行业资金流-即时首页", "http://data.10jqka.com.cn/funds/hyzjl/field/tradezdf/order/desc/ajax/1/free/1/",
         "http://data.10jqka.com.cn/funds/hyzjl/"),
        ("行业资金流-第2页", "http://data.10jqka.com.cn/funds/hyzjl/field/tradezdf/order/desc/page/2/ajax/1/free/1/",
         "http://data.10jqka.com.cn/funds/hyzjl/"),
        ("概念资金流-即时首页", "http://data.10jqka.com.cn/funds/gnzjl/field/tradezdf/order/desc/ajax/1/free/1/",
         "http://data.10jqka.com.cn/funds/gnzjl/"),
        ("概念资金流-第2页", "http://data.10jqka.com.cn/funds/gnzjl/field/tradezdf/order/desc/page/2/ajax/1/free/1/",
         "http://data.10jqka.com.cn/funds/gnzjl/"),
    ]

    for name, url, ref in apis:
        print(f"\n  [{name}]")
        print(f"  URL: {url}")
        try:
            resp = requests.get(url, headers=make_headers(token, ref), timeout=15)
            print(f"  Status: {resp.status_code}, Length: {len(resp.text)}")
            if resp.status_code == 200 and len(resp.text) > 50:
                text = resp.text
                if text.strip().startswith(("{", "[")):
                    data = json.loads(text)
                    arr_info = f"array[{len(data)}]" if isinstance(data, list) else ""
                    print(f"  JSON keys: {list(data.keys()) if isinstance(data, dict) else arr_info}")
                    print(f"  Preview: {json.dumps(data, ensure_ascii=False)[:500]}...")
                else:
                    rows = re.findall(r'<tr[^>]*>(.*?)</tr>', text, re.DOTALL)
                    print(f"  HTML rows: {len(rows)}")
                    if rows:
                        cells = re.findall(r'<td[^>]*>(.*?)</td>', rows[0], re.DOTALL)
                        cells_clean = [re.sub(r'<[^>]+>', '', c).strip() for c in cells]
                        print(f"  First row: {cells_clean[:10]}")
                    page_match = re.search(r'page_info[^>]*>1/(\d+)', text)
                    if page_match:
                        print(f"  Total pages: {page_match.group(1)}")
                    print(f"  Preview: {text[:300]}...")
            else:
                print(f"  Response: {resp.text[:200]}")
        except Exception as e:
            print(f"  Error: {e}")
        time.sleep(0.3)

    # ============================================================
    section("3. 龙虎榜")
    # ============================================================
    apis = [
        ("龙虎榜-当日详情", "https://data.10jqka.com.cn/market/lhb/cxg/",
         "https://data.10jqka.com.cn/market/lhb/"),
        ("龙虎榜-统计数据", "https://data.10jqka.com.cn/market/lhb/statistics/",
         "https://data.10jqka.com.cn/market/lhb/"),
        ("龙虎榜-席位明细", "https://data.10jqka.com.cn/market/lhb/seat/",
         "https://data.10jqka.com.cn/market/lhb/"),
        ("龙虎榜-近30日", "https://data.10jqka.com.cn/market/lhb/cxg/ajax/1/",
         "https://data.10jqka.com.cn/market/lhb/"),
    ]

    for name, url, ref in apis:
        print(f"\n  [{name}]")
        print(f"  URL: {url}")
        try:
            resp = requests.get(url, headers=make_headers(token, ref), timeout=15)
            print(f"  Status: {resp.status_code}, Length: {len(resp.text)}")
            if resp.status_code == 200 and len(resp.text) > 50:
                text = resp.text
                if text.strip().startswith(("{", "[")):
                    data = json.loads(text)
                    arr_info = f"array[{len(data)}]" if isinstance(data, list) else ""
                    print(f"  JSON keys: {list(data.keys()) if isinstance(data, dict) else arr_info}")
                    print(f"  Preview: {json.dumps(data, ensure_ascii=False)[:500]}...")
                else:
                    rows = re.findall(r'<tr[^>]*>(.*?)</tr>', text, re.DOTALL)
                    print(f"  HTML rows: {len(rows)}")
                    print(f"  Preview: {text[:300]}...")
            else:
                print(f"  Response: {resp.text[:200]}")
        except Exception as e:
            print(f"  Error: {e}")
        time.sleep(0.3)

    # ============================================================
    section("4. 个股资金流向")
    # ============================================================
    apis = [
        ("个股资金-即时首页", "http://data.10jqka.com.cn/funds/ggzjl/field/zdf/order/desc/ajax/1/free/1/",
         "http://data.10jqka.com.cn/funds/ggzjl/"),
        ("个股资金-第2页", "http://data.10jqka.com.cn/funds/ggzjl/field/zdf/order/desc/page/2/ajax/1/free/1/",
         "http://data.10jqka.com.cn/funds/ggzjl/"),
        ("个股资金-3日排行", "http://data.10jqka.com.cn/funds/ggzjl/board/3/field/zdf/order/desc/ajax/1/free/1/",
         "http://data.10jqka.com.cn/funds/ggzjl/"),
        ("大单追踪", "http://data.10jqka.com.cn/funds/ddzz/order/desc/ajax/1/free/1/",
         "http://data.10jqka.com.cn/funds/ddzz/"),
    ]

    for name, url, ref in apis:
        print(f"\n  [{name}]")
        print(f"  URL: {url}")
        try:
            resp = requests.get(url, headers=make_headers(token, ref), timeout=15)
            print(f"  Status: {resp.status_code}, Length: {len(resp.text)}")
            if resp.status_code == 200 and len(resp.text) > 50:
                text = resp.text
                if text.strip().startswith(("{", "[")):
                    data = json.loads(text)
                    arr_info = f"array[{len(data)}]" if isinstance(data, list) else ""
                    print(f"  JSON keys: {list(data.keys()) if isinstance(data, dict) else arr_info}")
                    print(f"  Preview: {json.dumps(data, ensure_ascii=False)[:500]}...")
                else:
                    rows = re.findall(r'<tr[^>]*>(.*?)</tr>', text, re.DOTALL)
                    print(f"  HTML rows: {len(rows)}")
                    if rows:
                        cells = re.findall(r'<td[^>]*>(.*?)</td>', rows[0], re.DOTALL)
                        cells_clean = [re.sub(r'<[^>]+>', '', c).strip() for c in cells]
                        print(f"  First row: {cells_clean[:10]}")
                    page_match = re.search(r'page_info[^>]*>1/(\d+)', text)
                    if page_match:
                        print(f"  Total pages: {page_match.group(1)}")
                    print(f"  Preview: {text[:300]}...")
            else:
                print(f"  Response: {resp.text[:200]}")
        except Exception as e:
            print(f"  Error: {e}")
        time.sleep(0.3)


def main():
    print("=" * 70)
    print("同花顺资金流向 API 验证 v2")
    print(f"时间: {datetime.now().strftime('%Y-%m-%d %H:%M:%S')}")
    print("=" * 70)

    # 尝试 py_mini_racer
    token = get_token_mini_racer()
    if not token:
        print("\npy_mini_racer 失败，使用随机 token 仅测试接口连通性")
        token = get_token_simple()

    test_all_apis(token)

    print("\n" + "=" * 70)
    print("验证完成")
    print("=" * 70)


if __name__ == "__main__":
    main()
