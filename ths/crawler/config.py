"""
同花顺资金流向爬虫 - 配置文件
"""
import os

# ClickHouse 连接配置
CLICKHOUSE = {
    "host": os.getenv("CK_HOST", "100.97.74.45"),
    "port": int(os.getenv("CK_PORT", "9000")),
    "database": os.getenv("CK_DB", "crawler"),
    "username": os.getenv("CK_USER", "default"),
    "password": os.getenv("CK_PASS", "pamirs@123"),
}

# 同花顺 API 配置
THS_BASE = "https://data.10jqka.com.cn"
THS_FUND_BASE = "http://data.10jqka.com.cn"  # 资金流用 http

# Token 获取配置
CHAMELEON_JS = os.path.join(os.path.dirname(__file__), "chameleon.1.13.min.js")
TOKEN_CACHE_SECONDS = 600  # token 缓存 10 分钟

# 请求配置
REQUEST_TIMEOUT = 15
REQUEST_DELAY = 0.3  # 请求间隔（秒）

# User-Agent
USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36"
