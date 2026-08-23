#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
同花顺资金流向 API 验证脚本
测试 4 个方向的数据接口，确认数据可用
需要 hexin-v token（从浏览器提取）
"""
import io
import json
import sys
import time
import requests
from datetime import datetime

sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding="utf-8", errors="replace")
sys.stderr = io.TextIOWrapper(sys.stderr.buffer, encoding="utf-8", errors="replace")

# ============================================================
# hexin-v token 获取
# ============================================================
TOKEN_SERVER = "http://localhost:9090"  # hexin-v-extractor 服务

HEXIN_V = None


def get_hexin_v_token():
    """从本地 hexin-v-extractor 服务获取 token"""
    global HEXIN_V
    try:
        resp = requests.post(
            f"{TOKEN_SERVER}/extract",
            json={"url": "https://data.10jqka.com.cn/"},
            timeout=30,
        )
        data = resp.json()
        if data.get("ok"):
            HEXIN_V = data["token"]
            print(f"[token] 获取成功: {HEXIN_V[:30]}...")
            return True
        else:
            print(f"[token] 获取失败: {data}")
            return False
    except Exception as e:
        print(f"[token] 连接失败: {e}")
        print("  请先启动 hexin-v-extractor: cd hexin-v-extractor && node server.js 9090")
        return False


def make_headers(referer="https://data.10jqka.com.cn/"):
    return {
        "Accept": "text/html, */*; q=0.01",
        "Accept-Encoding": "gzip, deflate",
        "Accept-Language": "zh-CN,zh;q=0.9,en;q=0.8",
        "Cache-Control": "no-cache",
        "Connection": "keep-alive",
        "hexin-v": HEXIN_V,
        "Host": "data.10jqka.com.cn",
        "Pragma": "no-cache",
        "Referer": referer,
        "User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36",
        "X-Requested-With": "XMLHttpRequest",
    }


def section(t):
    print(f"\n{'='*70}\n {t}\n{'='*70}")


# ============================================================
# 1. 北向资金
# ============================================================
def test_northbound():
    section("1. 同花顺北向资金 API 测试")

    urls = [
        ("北向资金汇总", "https://data.10jqka.com.cn/hsgt/api/zjlx/boardType/5"),
        ("北向资金个股排行", "https://data.10jqka.com.cn/hsgt/api/zjlx/boardType/5/page/1/ajax/1/"),
        ("沪深港通资金", "https://data.10jqka.com.cn/hsgt/api/zjlx/"),
    ]

    for name, url in urls:
        print(f"\n  [{name}]")
        print(f"  URL: {url}")
        try:
            resp = requests.get(url, headers=make_headers(), timeout=15)
            print(f"  Status: {resp.status_code}")
            print(f"  Content-Length: {len(resp.text)}")

            if resp.status_code == 200 and len(resp.text) > 100:
                try:
                    data = resp.json()
                    print(f"  JSON keys: {list(data.keys()) if isinstance(data, dict) else type(data)}")
                    # 打印前 200 字符
                    text = json.dumps(data, ensure_ascii=False)
                    print(f"  Preview: {text[:300]}...")
                except:
                    print(f"  Text preview: {resp.text[:300]}...")
            else:
                print(f"  Response: {resp.text[:200]}")
        except Exception as e:
            print(f"  Error: {e}")
        time.sleep(0.5)


# ============================================================
# 2. 板块资金流向
# ============================================================
def test_board_fund_flow():
    section("2. 同花顺板块资金流向 API 测试")

    urls = [
        ("行业资金流-即时", "http://data.10jqka.com.cn/funds/hyzjl/field/tradezdf/order/desc/ajax/1/free/1/"),
        ("行业资金流-第2页", "http://data.10jqka.com.cn/funds/hyzjl/field/tradezdf/order/desc/page/2/ajax/1/free/1/"),
        ("概念资金流-即时", "http://data.10jqka.com.cn/funds/gnzjl/field/tradezdf/order/desc/ajax/1/free/1/"),
        ("概念资金流-第2页", "http://data.10jqka.com.cn/funds/gnzjl/field/tradezdf/order/desc/page/2/ajax/1/free/1/"),
    ]

    for name, url in urls:
        print(f"\n  [{name}]")
        print(f"  URL: {url}")
        try:
            resp = requests.get(url, headers=make_headers("http://data.10jqka.com.cn/funds/hyzjl/"), timeout=15)
            print(f"  Status: {resp.status_code}")
            print(f"  Content-Length: {len(resp.text)}")

            if resp.status_code == 200:
                text = resp.text
                # 同花顺返回的是 HTML 表格或 JSON
                if text.strip().startswith(("{", "[")):
                    data = json.loads(text)
                    print(f"  JSON keys: {list(data.keys()) if isinstance(data, dict) else type(data)}")
                    print(f"  Preview: {json.dumps(data, ensure_ascii=False)[:400]}...")
                else:
                    # HTML 表格
                    # 尝试提取数据
                    import re
                    rows = re.findall(r'<tr[^>]*>(.*?)</tr>', text, re.DOTALL)
                    print(f"  HTML rows found: {len(rows)}")
                    if rows:
                        # 提取第一行数据
                        cells = re.findall(r'<td[^>]*>(.*?)</td>', rows[0], re.DOTALL)
                        print(f"  First row cells: {cells[:8]}")
                    # 提取总页数
                    page_match = re.search(r'page_info[^>]*>1/(\d+)', text)
                    if page_match:
                        print(f"  Total pages: {page_match.group(1)}")
                    print(f"  Preview: {text[:300]}...")
            else:
                print(f"  Response: {resp.text[:200]}")
        except Exception as e:
            print(f"  Error: {e}")
        time.sleep(0.5)


# ============================================================
# 3. 龙虎榜
# ============================================================
def test_dragon_tiger():
    section("3. 同花顺龙虎榜 API 测试")

    urls = [
        ("龙虎榜当日", "https://data.10jqka.com.cn/market/lhb/cxg/"),
        ("龙虎榜详情", "https://data.10jqka.com.cn/market/lhb/statistics/"),
        ("龙虎榜席位", "https://data.10jqka.com.cn/market/lhb/seat/"),
        ("龙虎榜近一月", "https://data.10jqka.com.cn/market/lhb/cxg/ajax/1/"),
    ]

    for name, url in urls:
        print(f"\n  [{name}]")
        print(f"  URL: {url}")
        try:
            resp = requests.get(url, headers=make_headers("https://data.10jqka.com.cn/market/lhb/"), timeout=15)
            print(f"  Status: {resp.status_code}")
            print(f"  Content-Length: {len(resp.text)}")

            if resp.status_code == 200 and len(resp.text) > 50:
                text = resp.text
                if text.strip().startswith(("{", "[")):
                    data = json.loads(text)
                    print(f"  JSON keys: {list(data.keys()) if isinstance(data, dict) else type(data)}")
                    print(f"  Preview: {json.dumps(data, ensure_ascii=False)[:400]}...")
                else:
                    import re
                    rows = re.findall(r'<tr[^>]*>(.*?)</tr>', text, re.DOTALL)
                    print(f"  HTML rows found: {len(rows)}")
                    print(f"  Preview: {text[:300]}...")
            else:
                print(f"  Response: {resp.text[:200]}")
        except Exception as e:
            print(f"  Error: {e}")
        time.sleep(0.5)


# ============================================================
# 4. 个股资金流向
# ============================================================
def test_stock_fund_flow():
    section("4. 同花顺个股资金流向 API 测试")

    urls = [
        ("个股资金流-即时", "http://data.10jqka.com.cn/funds/ggzjl/field/zdf/order/desc/ajax/1/free/1/"),
        ("个股资金流-第2页", "http://data.10jqka.com.cn/funds/ggzjl/field/zdf/order/desc/page/2/ajax/1/free/1/"),
        ("个股资金流-3日排行", "http://data.10jqka.com.cn/funds/ggzjl/board/3/field/zdf/order/desc/ajax/1/free/1/"),
        ("大单追踪", "http://data.10jqka.com.cn/funds/ddzz/order/desc/ajax/1/free/1/"),
    ]

    for name, url in urls:
        print(f"\n  [{name}]")
        print(f"  URL: {url}")
        try:
            resp = requests.get(url, headers=make_headers("http://data.10jqka.com.cn/funds/ggzjl/"), timeout=15)
            print(f"  Status: {resp.status_code}")
            print(f"  Content-Length: {len(resp.text)}")

            if resp.status_code == 200:
                text = resp.text
                if text.strip().startswith(("{", "[")):
                    data = json.loads(text)
                    print(f"  JSON keys: {list(data.keys()) if isinstance(data, dict) else type(data)}")
                    print(f"  Preview: {json.dumps(data, ensure_ascii=False)[:400]}...")
                else:
                    import re
                    rows = re.findall(r'<tr[^>]*>(.*?)</tr>', text, re.DOTALL)
                    print(f"  HTML rows found: {len(rows)}")
                    if rows:
                        cells = re.findall(r'<td[^>]*>(.*?)</td>', rows[0], re.DOTALL)
                        print(f"  First row cells: {cells[:10]}")
                    page_match = re.search(r'page_info[^>]*>1/(\d+)', text)
                    if page_match:
                        print(f"  Total pages: {page_match.group(1)}")
                    print(f"  Preview: {text[:300]}...")
            else:
                print(f"  Response: {resp.text[:200]}")
        except Exception as e:
            print(f"  Error: {e}")
        time.sleep(0.5)


# ============================================================
# Main
# ============================================================
def main():
    print("=" * 70)
    print("同花顺资金流向 API 验证")
    print(f"时间: {datetime.now().strftime('%Y-%m-%d %H:%M:%S')}")
    print("=" * 70)

    if not get_hexin_v_token():
        print("\n无法获取 token，退出")
        sys.exit(1)

    test_northbound()
    test_board_fund_flow()
    test_dragon_tiger()
    test_stock_fund_flow()

    print("\n" + "=" * 70)
    print("验证完成")
    print("=" * 70)


if __name__ == "__main__":
    main()
