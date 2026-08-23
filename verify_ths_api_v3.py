#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
同花顺资金流向 API 验证 v3
聚焦：1. 解析行业资金流 HTML 数据  2. 用 ths.js 正确实现获取 token
"""
import io
import json
import sys
import time
import re
import requests
from datetime import datetime

sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding="utf-8", errors="replace")
sys.stderr = io.TextIOWrapper(sys.stderr.buffer, encoding="utf-8", errors="replace")

# ============================================================
# 用 akshare 的 ths.js + py_mini_racer（正确 mock）
# ============================================================
def get_token_akshare():
    """用 akshare 的方式获取 token"""
    try:
        from py_mini_racer import MiniRacer
    except ImportError:
        print("[token] py_mini_racer 未安装")
        return None

    # 读取 chameleon JS
    js_path = "hexin-v-extractor/chameleon.1.13.min.js"
    with open(js_path, "r", encoding="utf-8") as f:
        js_code = f.read()

    ctx = MiniRacer()

    # 正确的浏览器环境 mock（参考 akshare 的实现）
    mock = """
    (function(){
        // window 对象
        window = {};
        window.navigator = navigator;
        window.document = document;

        // document
        document = {};
        document.createElement = function(tag) {
            var elem = {};
            elem.style = {};
            elem.setAttribute = function(){};
            elem.appendChild = function(){};
            elem.addEventListener = function(){};
            elem.removeChild = function(){};
            elem.getContext = function(){ return null; };
            elem.toDataURL = function(){ return ''; };
            elem.getExtension = function(){ return null; };
            return elem;
        };
        document.addEventListener = function(){};
        document.removeEventListener = function(){};
        document.cookie = '';
        document.referrer = 'https://data.10jqka.com.cn/';
        document.documentElement = {};
        document.documentElement.style = {};
        document.body = {};
        document.body.appendChild = function(){};
        document.body.removeChild = function(){};
        document.body.style = {};
        document.body.innerHTML = '';
        document.body.className = '';
        document.body.id = '';
        document.body.tagName = 'BODY';
        document.body.children = [];
        document.body.childNodes = [];
        document.body.parentNode = null;
        document.body.offsetTop = 0;
        document.body.offsetLeft = 0;
        document.body.offsetWidth = 0;
        document.body.offsetHeight = 0;

        // navigator
        navigator = {};
        navigator.userAgent = 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36';
        navigator.appVersion = '5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36';
        navigator.platform = 'Win32';
        navigator.language = 'zh-CN';
        navigator.languages = ['zh-CN','zh'];
        navigator.cookieEnabled = true;
        navigator.plugins = [];
        navigator.mimeTypes = [];
        navigator.appName = 'Netscape';
        navigator.appCodeName = 'Mozilla';
        navigator.product = 'Gecko';
        navigator.productSub = '20030107';
        navigator.vendor = 'Google Inc.';
        navigator.vendorSub = '';
        navigator.getBattery = function(){ return Promise.resolve({level:1,charging:true}); };
        navigator.getGamepads = function(){ return []; };
        navigator.hardwareConcurrency = 8;
        navigator.maxTouchPoints = 0;
        navigator.onLine = true;

        // screen
        screen = {};
        screen.width = 1920;
        screen.height = 1080;
        screen.availWidth = 1920;
        screen.availHeight = 1040;
        screen.colorDepth = 24;
        screen.pixelDepth = 24;

        // location
        location = {};
        location.href = 'https://data.10jqka.com.cn/';
        location.hostname = 'data.10jqka.com.cn';
        location.protocol = 'https:';
        location.port = '';
        location.pathname = '/';
        location.search = '';
        location.hash = '';
        location.origin = 'https://data.10jqka.com.cn';

        // 其他全局对象
        location.assign = function(){};
        location.reload = function(){};
        location.replace = function(){};
        location.toString = function(){ return location.href; };
        Image = function(){};
        Image.prototype.addEventListener = function(){};
        Image.prototype.removeEventListener = function(){};
        AudioContext = function(){};
        AudioContext.prototype.createOscillator = function(){ return {frequency:{value:0}, connect:function(){}, start:function(){}, stop:function(){}}; };
        AudioContext.prototype.createAnalyser = function(){ return {frequencyBinCount:0, connect:function(){}, getByteFrequencyData:function(){}}; };
        AudioContext.prototype.createGain = function(){ return {gain:{value:0}, connect:function(){}}; };
        AudioContext.prototype.createScriptProcessor = function(){ return {connect:function(){}, addEventListener:function(){}}; };
        AudioContext.prototype.destination = {};
        AudioContext.prototype.sampleRate = 44100;
        AudioContext.prototype.currentTime = 0;
        HTMLElement = function(){};
        CanvasRenderingContext2D = function(){};
        CanvasRenderingContext2D.prototype.fillText = function(){};
        CanvasRenderingContext2D.prototype.getImageData = function(){ return {data:[]}; };
        CanvasRenderingContext2D.prototype.fillRect = function(){};
        CanvasRenderingContext2D.prototype.createLinearGradient = function(){ return {addColorStop:function(){}}; };
        CanvasRenderingContext2D.prototype.beginPath = function(){};
        CanvasRenderingContext2D.prototype.moveTo = function(){};
        CanvasRenderingContext2D.prototype.lineTo = function(){};
        CanvasRenderingContext2D.prototype.stroke = function(){};
        CanvasRenderingContext2D.prototype.fill = function(){};
        CanvasRenderingContext2D.prototype.arc = function(){};
        CanvasRenderingContext2D.prototype.closePath = function(){};
        CanvasRenderingContext2D.prototype.rect = function(){};
        CanvasRenderingContext2D.prototype.strokeRect = function(){};
        CanvasRenderingContext2D.prototype.clearRect = function(){};
        CanvasRenderingContext2D.prototype.save = function(){};
        CanvasRenderingContext2D.prototype.restore = function(){};
        CanvasRenderingContext2D.prototype.translate = function(){};
        CanvasRenderingContext2D.prototype.rotate = function(){};
        CanvasRenderingContext2D.prototype.scale = function(){};
        CanvasRenderingContext2D.prototype.measureText = function(){ return {width:0}; };
        CanvasRenderingContext2D.prototype.font = '';
        CanvasRenderingContext2D.prototype.fillStyle = '';
        CanvasRenderingContext2D.prototype.strokeStyle = '';
        CanvasRenderingContext2D.prototype.lineWidth = 1;
        CanvasRenderingContext2D.prototype.textAlign = '';
        CanvasRenderingContext2D.prototype.textBaseline = '';
        CanvasRenderingContext2D.prototype.globalAlpha = 1;
        CanvasRenderingContext2D.prototype.globalCompositeOperation = '';
        Performance = function(){};
        Performance.prototype.now = function(){ return Date.now(); };
        URL = function(){};
        URL.createObjectURL = function(){ return ''; };
        URL.revokeObjectURL = function(){};
        requestAnimationFrame = function(){ return 0; };
        cancelAnimationFrame = function(){};
        setTimeout = function(){ return 0; };
        clearTimeout = function(){};
        setInterval = function(){ return 0; };
        clearInterval = function(){};
        Promise = Promise || function(){};
        WebSocket = function(){};
        XMLHttpRequest = function(){};
        XMLHttpRequest.prototype.open = function(){};
        XMLHttpRequest.prototype.send = function(){};
        XMLHttpRequest.prototype.setRequestHeader = function(){};
        XMLHttpRequest.prototype.getAllResponseHeaders = function(){ return ''; };
        XMLHttpRequest.prototype.getResponseHeader = function(){ return ''; };
        XMLHttpRequest.prototype.abort = function(){};
        XMLHttpRequest.prototype.addEventListener = function(){};
        XMLHttpRequest.prototype.removeEventListener = function(){};
        XMLHttpRequest.prototype.readyState = 4;
        XMLHttpRequest.prototype.status = 200;
        XMLHttpRequest.prototype.responseText = '';
        XMLHttpRequest.prototype.response = '';
        XMLHttpRequest.prototype.responseType = '';
        XMLHttpRequest.prototype.withCredentials = false;
        XMLHttpRequest.prototype.timeout = 0;
        XMLHttpRequest.prototype.ontimeout = null;
        XMLHttpRequest.prototype.onerror = null;
        XMLHttpRequest.prototype.onreadystatechange = null;
        XMLHttpRequest.prototype.onload = null;
        XMLHttpRequest.prototype.onloadstart = null;
        XMLHttpRequest.prototype.onloadend = null;
        XMLHttpRequest.prototype.onprogress = null;
        indexedDB = {};
        indexedDB.open = function(){};
        caches = {};
        caches.open = function(){};
        caches.match = function(){};
        caches.has = function(){};
        caches.delete = function(){};
        caches.keys = function(){};
        localStorage = {};
        localStorage.getItem = function(){ return null; };
        localStorage.setItem = function(){};
        localStorage.removeItem = function(){};
        localStorage.clear = function(){};
        sessionStorage = {};
        sessionStorage.getItem = function(){ return null; };
        sessionStorage.setItem = function(){};
        sessionStorage.removeItem = function(){};
        sessionStorage.clear = function(){};

        // 返回全局对象
        return {window:window, document:document, navigator:navigator, screen:screen, location:location};
    })();
    """

    try:
        ctx.eval(mock)
        ctx.eval(js_code)
        # chameleon JS 定义了 CHAMELEON_CALLBACK 回调
        token = ctx.call("v")
        print(f"[token] 获取成功: {str(token)[:50]}...")
        return str(token)
    except Exception as e:
        print(f"[token] 执行失败: {e}")
        return None


# ============================================================
# 测试
# ============================================================
def section(t):
    print(f"\n{'='*70}\n {t}\n{'='*70}")


def make_headers(token, referer="https://data.10jqka.com.cn/"):
    return {
        "Accept": "text/html, */*; q=0.01",
        "Accept-Encoding": "gzip, deflate",
        "Accept-Language": "zh-CN,zh;q=0.9,en;q=0.8",
        "Cache-Control": "no-cache",
        "Connection": "keep-alive",
        "hexin-v": token or "",
        "Host": "data.10jqka.com.cn",
        "Pragma": "no-cache",
        "Referer": referer,
        "User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36",
        "X-Requested-With": "XMLHttpRequest",
    }


def test_with_token(token):
    """用获取到的 token 测试所有 API"""

    section("1. 北向资金")
    apis = [
        ("沪深港通-汇总", "https://data.10jqka.com.cn/hsgt/api/zjlx/", "https://data.10jqka.com.cn/hsgt/"),
        ("个股排行", "https://data.10jqka.com.cn/hsgt/api/zjlx/boardType/5/page/1/ajax/1/", "https://data.10jqka.com.cn/hsgt/"),
    ]
    for name, url, ref in apis:
        print(f"\n  [{name}]")
        try:
            resp = requests.get(url, headers=make_headers(token, ref), timeout=15)
            print(f"  Status: {resp.status_code}, Length: {len(resp.text)}")
            if resp.status_code == 200 and len(resp.text) > 50:
                if resp.text.strip().startswith(("{","[")):
                    data = resp.json()
                    print(f"  JSON: {json.dumps(data, ensure_ascii=False)[:500]}...")
                else:
                    print(f"  Text: {resp.text[:300]}...")
        except Exception as e:
            print(f"  Error: {e}")
        time.sleep(0.3)

    section("2. 板块资金流向")
    apis = [
        ("行业资金流-p1", "http://data.10jqka.com.cn/funds/hyzjl/field/tradezdf/order/desc/ajax/1/free/1/", "http://data.10jqka.com.cn/funds/hyzjl/"),
        ("行业资金流-p2", "http://data.10jqka.com.cn/funds/hyzjl/field/tradezdf/order/desc/page/2/ajax/1/free/1/", "http://data.10jqka.com.cn/funds/hyzjl/"),
        ("概念资金流-p1", "http://data.10jqka.com.cn/funds/gnzjl/field/tradezdf/order/desc/ajax/1/free/1/", "http://data.10jqka.com.cn/funds/gnzjl/"),
    ]
    for name, url, ref in apis:
        print(f"\n  [{name}]")
        try:
            resp = requests.get(url, headers=make_headers(token, ref), timeout=15)
            print(f"  Status: {resp.status_code}, Length: {len(resp.text)}")
            if resp.status_code == 200 and len(resp.text) > 100:
                text = resp.text
                rows = re.findall(r'<tr[^>]*>(.*?)</tr>', text, re.DOTALL)
                print(f"  HTML rows: {len(rows)}")
                if rows:
                    # 解析每行数据
                    for i, row in enumerate(rows[:3]):
                        cells = re.findall(r'<td[^>]*>(.*?)</td>', row, re.DOTALL)
                        cells_clean = [re.sub(r'<[^>]+>', '', c).strip() for c in cells]
                        print(f"  Row {i}: {cells_clean[:10]}")
            else:
                print(f"  Response: {resp.text[:200]}")
        except Exception as e:
            print(f"  Error: {e}")
        time.sleep(0.3)

    section("3. 龙虎榜")
    apis = [
        ("龙虎榜-当日", "https://data.10jqka.com.cn/market/lhb/cxg/", "https://data.10jqka.com.cn/market/lhb/"),
        ("龙虎榜-统计", "https://data.10jqka.com.cn/market/lhb/statistics/", "https://data.10jqka.com.cn/market/lhb/"),
        ("龙虎榜-席位", "https://data.10jqka.com.cn/market/lhb/seat/", "https://data.10jqka.com.cn/market/lhb/"),
    ]
    for name, url, ref in apis:
        print(f"\n  [{name}]")
        try:
            resp = requests.get(url, headers=make_headers(token, ref), timeout=15)
            print(f"  Status: {resp.status_code}, Length: {len(resp.text)}")
            if resp.status_code == 200 and len(resp.text) > 50:
                if resp.text.strip().startswith(("{","[")):
                    data = resp.json()
                    print(f"  JSON: {json.dumps(data, ensure_ascii=False)[:500]}...")
                else:
                    rows = re.findall(r'<tr[^>]*>(.*?)</tr>', resp.text, re.DOTALL)
                    print(f"  HTML rows: {len(rows)}")
                    print(f"  Text: {resp.text[:300]}...")
            else:
                print(f"  Response: {resp.text[:200]}")
        except Exception as e:
            print(f"  Error: {e}")
        time.sleep(0.3)

    section("4. 个股资金流向")
    apis = [
        ("个股资金-p1", "http://data.10jqka.com.cn/funds/ggzjl/field/zdf/order/desc/ajax/1/free/1/", "http://data.10jqka.com.cn/funds/ggzjl/"),
        ("个股资金-p2", "http://data.10jqka.com.cn/funds/ggzjl/field/zdf/order/desc/page/2/ajax/1/free/1/", "http://data.10jqka.com.cn/funds/ggzjl/"),
        ("大单追踪", "http://data.10jqka.com.cn/funds/ddzz/order/desc/ajax/1/free/1/", "http://data.10jqka.com.cn/funds/ddzz/"),
    ]
    for name, url, ref in apis:
        print(f"\n  [{name}]")
        try:
            resp = requests.get(url, headers=make_headers(token, ref), timeout=15)
            print(f"  Status: {resp.status_code}, Length: {len(resp.text)}")
            if resp.status_code == 200 and len(resp.text) > 50:
                text = resp.text
                if text.strip().startswith(("{","[")):
                    data = json.loads(text)
                    print(f"  JSON: {json.dumps(data, ensure_ascii=False)[:500]}...")
                else:
                    rows = re.findall(r'<tr[^>]*>(.*?)</tr>', text, re.DOTALL)
                    print(f"  HTML rows: {len(rows)}")
                    if rows:
                        cells = re.findall(r'<td[^>]*>(.*?)</td>', rows[0], re.DOTALL)
                        cells_clean = [re.sub(r'<[^>]+>', '', c).strip() for c in cells]
                        print(f"  First row: {cells_clean[:10]}")
            else:
                print(f"  Response: {resp.text[:200]}")
        except Exception as e:
            print(f"  Error: {e}")
        time.sleep(0.3)


def main():
    print("=" * 70)
    print("同花顺资金流向 API 验证 v3")
    print(f"时间: {datetime.now().strftime('%Y-%m-%d %H:%M:%S')}")
    print("=" * 70)

    token = get_token_akshare()
    if not token:
        print("\n无法获取有效 token，退出")
        sys.exit(1)

    test_with_token(token)

    print("\n" + "=" * 70)
    print("验证完成")
    print("=" * 70)


if __name__ == "__main__":
    main()
