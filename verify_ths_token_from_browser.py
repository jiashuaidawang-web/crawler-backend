#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
从 CloakBrowser (CDP 9222) 提取 hexin-v token
"""
import io
import json
import sys
import requests
from datetime import datetime

sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding="utf-8", errors="replace")
sys.stderr = io.TextIOWrapper(sys.stderr.buffer, encoding="utf-8", errors="replace")

CDP_URL = "http://127.0.0.1:9222"


def get_token_from_cdp():
    """通过 CDP 连接 CloakBrowser 获取 hexin-v cookie"""
    print("[CDP] 检查 CloakBrowser 是否在 9222 端口运行...")

    try:
        # 检查 CDP 是否可用
        resp = requests.get(f"{CDP_URL}/json/version", timeout=5)
        if resp.status_code != 200:
            print(f"[CDP] 端口不可用: {resp.status_code}")
            return None
        ver = resp.json()
        print(f"[CDP] 浏览器: {ver.get('Browser', 'unknown')}")
    except Exception as e:
        print(f"[CDP] 连接失败: {e}")
        return None

    # 获取页面列表
    try:
        resp = requests.get(f"{CDP_URL}/json", timeout=5)
        pages = resp.json()
        print(f"[CDP] 当前页面数: {len(pages)}")
        for p in pages[:3]:
            print(f"  - {p.get('title','?')}: {p.get('url','?')[:60]}")
    except Exception as e:
        print(f"[CDP] 获取页面列表失败: {e}")

    # 提取 cookie
    try:
        # 通过 CDP HTTP API 获取 cookies
        resp = requests.get(f"{CDP_URL}/json", timeout=5)
        pages = resp.json()
        if pages:
            # 用第一个页面的 websocket endpoint 来执行 JS
            ws_url = pages[0].get("webSocketDebuggerUrl")
            if ws_url:
                print(f"[CDP] WebSocket: {ws_url[:60]}...")
                # 用 websocket-client 连接
                import websocket
                ws = websocket.create_connection(ws_url, timeout=10)

                # 发送获取 cookies 命令
                cmd = {
                    "id": 1,
                    "method": "Network.getCookies",
                    "params": {"urls": ["https://data.10jqka.com.cn"]}
                }
                ws.send(json.dumps(cmd))
                result = json.loads(ws.recv())
                ws.close()

                cookies = result.get("result", {}).get("cookies", [])
                print(f"[CDP] 获取到 {len(cookies)} 个 cookies")

                # 找 hexin-v (cookie 名 'v')
                for c in cookies:
                    if c["name"] == "v":
                        token = c["value"]
                        print(f"[CDP] 找到 token: {token[:50]}...")
                        return token

                # 如果没找到，打印所有 cookie 名
                print("[CDP] 未找到 'v' cookie，所有 cookie:")
                for c in cookies:
                    print(f"  {c['name']}: {c['value'][:30]}...")
    except ImportError:
        print("[CDP] websocket-client 未安装，尝试用 subprocess + curl")
    except Exception as e:
        print(f"[CDP] 提取失败: {e}")

    return None


def test_api_with_token(token):
    """用 token 测试 API"""
    if not token:
        return

    headers = {
        "Accept": "text/html, */*; q=0.01",
        "Accept-Encoding": "gzip, deflate",
        "Accept-Language": "zh-CN,zh;q=0.9",
        "Host": "data.10jqka.com.cn",
        "hexin-v": token,
        "Referer": "http://data.10jqka.com.cn/funds/hyzjl/",
        "User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36",
        "X-Requested-With": "XMLHttpRequest",
    }

    print("\n[测试] 行业资金流 API:")
    resp = requests.get(
        "http://data.10jqka.com.cn/funds/hyzjl/field/tradezdf/order/desc/ajax/1/free/1/",
        headers=headers, timeout=15
    )
    print(f"  Status: {resp.status_code}, Length: {len(resp.text)}")
    if resp.status_code == 200:
        import re
        rows = re.findall(r'<tr[^>]*>(.*?)</tr>', resp.text, re.DOTALL)
        print(f"  Rows: {len(rows)}")
        if rows:
            cells = re.findall(r'<td[^>]*>(.*?)</td>', rows[0], re.DOTALL)
            cells_clean = [re.sub(r'<[^>]+>', '', c).strip() for c in cells]
            print(f"  First row: {cells_clean[:10]}")


def main():
    print("=" * 70)
    print("从 CloakBrowser CDP 提取 hexin-v token")
    print(f"时间: {datetime.now().strftime('%Y-%m-%d %H:%M:%S')}")
    print("=" * 70)

    token = get_token_from_cdp()
    if token:
        test_api_with_token(token)
    else:
        print("\n无法从 CDP 获取 token")
        print("请确保 CloakBrowser 已启动: python scripts/cloak_serve.py")


if __name__ == "__main__":
    main()
