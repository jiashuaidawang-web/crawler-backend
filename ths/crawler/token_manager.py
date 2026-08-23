"""
同花顺 hexin-v Token 管理器
使用 jsdom (Node.js) 执行 chameleon JS 获取 token

破解思路：
1. 同花顺网站用 chameleon JS 生成 hexin-v token，作为 API 鉴权
2. chameleon JS 在浏览器中执行，通过 Canvas/字体渲染等指纹计算 token
3. 我们没有真实浏览器，但可以用 jsdom 模拟 DOM 环境执行 chameleon
4. jsdom 缺少 Canvas 支持，但 chameleon 有降级逻辑，仍能产出有效 token
5. token 有效期约 10 分钟，缓存复用

下一步如果失效：
- 检查 chameleon 版本是否更新（当前 chameleon.1.13.min.js）
- 从 https://s.thsi.cn/js/chameleon/ 下载最新版
- 如果 jsdom 不再可用，考虑用 Playwright headless Chrome 执行
"""
import subprocess
import json
import time
import os
from threading import Lock

from config import CHAMELEON_JS, TOKEN_CACHE_SECONDS


class TokenManager:
    def __init__(self):
        self._token = None
        self._expires_at = 0
        self._lock = Lock()
        # 验证 Node.js 可用
        self._check_node()

    def _check_node(self):
        """验证 Node.js 环境"""
        try:
            r = subprocess.run(
                ["node", "-e", "console.log('ok')"],
                capture_output=True, text=True, timeout=5
            )
            if r.returncode != 0:
                raise RuntimeError("Node.js 不可用")
        except FileNotFoundError:
            raise RuntimeError(
                "Node.js 未安装！请先安装 Node.js: https://nodejs.org/"
            )

    def get_token(self):
        """获取有效 token（带缓存）"""
        with self._lock:
            now = time.time()
            if self._token and now < self._expires_at:
                return self._token
            # 刷新 token
            self._token = self._fetch_token()
            self._expires_at = now + TOKEN_CACHE_SECONDS
            return self._token

    def _fetch_token(self):
        """执行 Node.js 脚本获取 token"""
        script_path = os.path.join(os.path.dirname(__file__), "_get_token.js")
        try:
            r = subprocess.run(
                ["node", script_path, CHAMELEON_JS],
                capture_output=True, text=True, timeout=45
            )
            token = r.stdout.strip()
            if not token or "NO_TOKEN" in token:
                raise RuntimeError(f"获取 token 失败: {r.stderr[:200]}")
            return token
        except subprocess.TimeoutExpired:
            raise RuntimeError("获取 token 超时")

    def invalidate(self):
        """强制刷新 token（请求失败时调用）"""
        with self._lock:
            self._token = None
            self._expires_at = 0


# 单例
_manager = None

def get_token_manager():
    global _manager
    if _manager is None:
        _manager = TokenManager()
    return _manager
