"""
同花顺 API 客户端
负责 HTTP 请求、HTML 表格解析
"""
import time
import requests
from bs4 import BeautifulSoup

from config import (
    USER_AGENT, REQUEST_TIMEOUT, REQUEST_DELAY,
    THS_BASE, THS_FUND_BASE
)
from token_manager import get_token_manager


class ThsApiClient:
    def __init__(self):
        self.session = requests.Session()
        self._last_request_time = 0

    def _get_headers(self, referer, host=None):
        """构造请求头"""
        token = get_token_manager().get_token()
        if host is None:
            host = THS_BASE.replace("https://", "").replace("http://", "")
        return {
            "Accept": "text/html, */*; q=0.01",
            "Accept-Encoding": "gzip, deflate",
            "Accept-Language": "zh-CN,zh;q=0.9",
            "Host": host,
            "hexin-v": token,
            "Referer": referer,
            "User-Agent": USER_AGENT,
            "X-Requested-With": "XMLHttpRequest",
        }

    def _throttle(self):
        """请求节流"""
        now = time.time()
        elapsed = now - self._last_request_time
        if elapsed < REQUEST_DELAY:
            time.sleep(REQUEST_DELAY - elapsed)
        self._last_request_time = time.time()

    def get(self, url, referer):
        """GET 请求"""
        self._throttle()
        # 根据 url 确定 host
        from urllib.parse import urlparse
        parsed = urlparse(url)
        host = parsed.netloc
        headers = self._get_headers(referer, host)
        try:
            resp = self.session.get(url, headers=headers, timeout=REQUEST_TIMEOUT)
            if resp.status_code == 401:
                # token 失效，刷新重试
                get_token_manager().invalidate()
                headers = self._get_headers(referer, host)
                resp = self.session.get(url, headers=headers, timeout=REQUEST_TIMEOUT)
            resp.raise_for_status()
            # 同花顺用 GBK 编码
            resp.encoding = "gbk"
            return resp.text
        except requests.RequestException as e:
            print(f"[API] 请求失败 {url}: {e}")
            raise

    def parse_html_table(self, html):
        """解析 HTML 表格为 list[dict]"""
        soup = BeautifulSoup(html, "html.parser")
        table = soup.find("table")
        if not table:
            return [], []

        # 提取表头（可能在独立的 thead/table 中）
        headers = []
        thead = table.find("thead")
        if thead:
            for th in thead.find_all(["th", "td"]):
                headers.append(th.get_text(strip=True))

        # 提取数据行：优先找 tbody，否则找包含数据的另一个 table
        rows = []
        tbody = table.find("tbody")
        if tbody:
            data_source = tbody
        else:
            # thead 和 tbody 分离的情况（如龙虎榜）：找下一个包含 tr 的 table
            data_source = table
            next_table = table.find_next("table")
            if next_table and next_table.find("td"):
                data_source = next_table

        for tr in data_source.find_all("tr"):
            # 跳过表头行
            if tr.find("th"):
                continue
            cells = []
            for td in tr.find_all("td"):
                cells.append(td.get_text(strip=True))
            if cells and any(cells):  # 跳过空行
                rows.append(cells)

        return headers, rows

    def get_total_pages(self, html):
        """提取总页数（page_info 格式: 当前页/总页数）"""
        import re
        match = re.search(r'class="page_info"[^>]*>\d+/(\d+)', html)
        if match:
            return int(match.group(1))
        return 1
