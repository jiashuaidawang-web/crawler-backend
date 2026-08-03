#!/usr/bin/env python3
"""
启动 CloakBrowser 并暴露 CDP 远程调试端口,供 Java worker 通过 Playwright connectOverCDP 接入。

环境变量:
  CLOAK_PORT                监听端口(默认 9222)
  CLOAKBROWSER_LICENSE_KEY  license key(空=用免费版)
  CLOAK_PROXY               代理串 http://user:pass@host:port
  CLOAK_HEADLESS            true/false(默认 true)
  CLOAK_HUMANIZE            true/false(默认 true)
  CLOAK_FINGERPRINT_SEED    固定指纹 seed(空=每次随机)

退出码:
  0   正常退出(被外部终止)
  2   启动失败(参数/依赖/binary 问题)
"""
import os
import sys
import subprocess
import time


def die(msg, code=2):
    print(f"[cloak_serve] FATAL: {msg}", file=sys.stderr, flush=True)
    sys.exit(code)


def main():
    print("[cloak_serve] importing cloakbrowser...", flush=True)
    try:
        import cloakbrowser
        from cloakbrowser import launch
    except ImportError as e:
        die(f"cloakbrowser not installed: {e}\nFix: pip install cloakbrowser")

    print(f"[cloak_serve] cloakbrowser version ok", flush=True)

    # 确保 binary 已下载
    try:
        from cloakbrowser import ensure_binary
        ensure_binary()
    except Exception as e:
        die(f"ensure_binary failed: {e}\nFix: python3 -m cloakbrowser install")

    port = int(os.environ.get("CLOAK_PORT", "9222"))
    license_key = os.environ.get("CLOAKBROWSER_LICENSE_KEY", "")
    proxy = os.environ.get("CLOAK_PROXY", "")
    headless = os.environ.get("CLOAK_HEADLESS", "true").lower() == "true"
    humanize = os.environ.get("CLOAK_HUMANIZE", "true").lower() == "true"
    seed = os.environ.get("CLOAK_FINGERPRINT_SEED", "")

    # 构建 launch 参数
    launch_kwargs = {
        "headless": headless,
        "humanize": humanize,
    }
    if license_key:
        launch_kwargs["license_key"] = license_key
    if proxy:
        launch_kwargs["proxy"] = proxy
    extra_args = []
    if seed:
        extra_args += ["--fingerprint", seed]
    if extra_args:
        launch_kwargs["args"] = extra_args

    # 关键:开远程调试端口,让 Java 端 connectOverCDP 接进来
    extra_args += [f"--remote-debugging-port={port}"]
    launch_kwargs["args"] = extra_args

    print(f"[cloak_serve] launching: headless={headless} humanize={humanize} port={port}", flush=True)
    if proxy:
        print(f"[cloak_serve] proxy={proxy}", flush=True)

    try:
        browser = launch(**launch_kwargs)
    except Exception as e:
        die(f"launch failed: {e}")

    print(f"[cloak_serve] browser launched, CDP endpoint should be on port {port}", flush=True)

    # 用 playwright 获取 websocket URL 并打印(方便诊断)
    try:
        from playwright.sync_api import sync_playwright
        pw = sync_playwright().start()
        connected = pw.chromium.connect_over_cdp(f"http://127.0.0.1:{port}")
        ctx = connected.new_context()
        page = ctx.new_page()
        page.goto("about:blank")
        print(f"[cloak_serve] self-test OK: CDP reachable on port {port}", flush=True)
        page.close()
        ctx.close()
        connected.close()
        pw.stop()
    except Exception as e:
        print(f"[cloak_serve] self-test CDP connect failed: {e}", file=sys.stderr, flush=True)

    # 挂起:保持浏览器进程活着,直到被外部终止
    try:
        while True:
            time.sleep(5)
    except KeyboardInterrupt:
        print("[cloak_serve] shutting down (KeyboardInterrupt)", flush=True)
    finally:
        try:
            browser.close()
        except Exception:
            pass
        print("[cloak_serve] exited cleanly", flush=True)
        sys.exit(0)


if __name__ == "__main__":
    try:
        main()
    except SystemExit:
        raise
    except Exception as e:
        die(f"unexpected error: {e}")
