# CloakBrowser 集成说明

给同花顺策略加 CloakBrowser(C++ 源码级 stealth)作为可选项,**默认不启用**,完全向后兼容。

## 1. 改了什么

| 文件 | 动作 | 说明 |
|---|---|---|
| `crawler-core/.../AntiCrawlConfig.java` | 接口加 10 个 cloak 相关方法 | stealthMode / cloakCdpUrl / cloakLicenseKey / cloakHumanize / cloakFingerprintSeed / cloakLocalPort / cloakServeScript |
| `crawler-worker/.../AntiCrawlConfig.java` | 实现加字段 + 默认值 | 全部走 `anti-crawl.*` yml 前缀 |
| `BrowserPool.java` | `acquire()` → `acquire(cfg)` | SELF/CLOAK 两个分支 |
| `BrowserContextFactory.java` | 加 CLOAK 分支 | CLOAK 模式下跳过 stealth JS / UA / viewport / proxy 注入(由 cloakserve 处理);保留 Cookie |
| `TonghuashunBrowserStrategy.java` | `acquire(cfg)` | 1 行 |
| `CloakServerProcess.java` | **新增** | 本机开发时自动拉起本地 cloakserve(Docker sidecar 模式下是 no-op) |
| `scripts/cloak_serve.py` | **新增** | Python 启动脚本,透传关键 env |
| `application.yml` | 加注释掉的 cloak 参数 | 默认 SELF |
| `Dockerfile.worker` | 加 Python + 字体 + 预下载 binary | 生产用 |
| `docker-compose.yml` | 加 `cloakserve` sidecar(默认 profile=cloak 不启) | 生产用 |

## 2. 本机调试(Mac / Linux)—— 无需 Docker

### 2.1 装cloakbrowser

```bash
pip install cloakbrowser
python3 -m cloakbrowser install   # 下载 binary(~200MB)
```

Mac 首次运行可能触发 Gatekeeper,跑一次:

```bash
xattr -cr ~/.cloakbrowser/chromium-*/Chromium.app
```

### 2.2 免费 key(可选,提升效果)

到 <https://cloakbrowser.dev/github> 登录获取免费 key,能拿到最新构建(71 补丁)。
不拿 key 也能跑,但只用的 v146 公开版(58 补丁)。

### 2.3 开 CLOAK 模式(两种方式)

**方式 A:改 application.yml(建议)**

取消 `crawler-worker/src/main/resources/application.yml` 里这几行注释并填值:

```yaml
anti-crawl:
  stealth-mode: CLOAK
  cloak-license-key: "cb_xxxxxxxx"   # 免费 key 或留空
  cloak-humanize: true
  cloak-fingerprint-seed: ""         # 留空=每次随机;填固定值=回访者身份
  cloak-local-port: 9222
  cloak-serve-script: scripts/cloak_serve.py
```

**方式 B:环境变量(不改文件)**

Spring Boot 支持 relaxed binding,直接:

```bash
ANTI_CRAWL_STEALTH_MODE=CLOAK ANTI_CRAWL_CLOAK_LICENSE_KEY=cb_xxx ./mvn spring-boot:run -pl crawler-worker
```

### 2.4 直接跑

在 IDEA 里 **直接 Run `CrawlerWorkerApplication`**(或你 worker 的主类)。

首次 Tonghuashun 策略触发 `BrowserPool.acquire()` 时:

1. `CloakServerProcess` 探测 9222 端口
2. 没人在监听 → 启动 `scripts/cloak_serve.py`(自动下载 binary + 启动)
3. 轮询直到端口就绪
4. Java 端 `playwright.chromium().connectOverCDP("http://127.0.0.1:9222")` 接入

整个过程对 `TonghuashunBrowserStrategy` 透明——**Java 代码里只在 `BrowserPool` 里多了一个 CDP 分支**,业务逻辑、字段抽取完全不动。

### 2.5 验证是否真的走 CLOAK

看日志:

```
[BrowserPool] CLOAK mode: connecting CDP server at http://127.0.0.1:9222
[CloakServerProcess] starting cloakserve via .../scripts/cloak_serve.py (port 9222)
[CloakServerProcess] cloakserve ready on port 9222
```

如果看到 `[BrowserPool] SELF mode: launching headless Chromium`,说明 yml 没配对。

## 3. 生产部署(Docker)

### 3.1 起 cloakserve sidecar

```bash
# .env 里放 key 和代理
export CLOAK_LICENSE_KEY=cb_xxx
export CLOAK_PROXY=http://user:pass@proxy:port

# 启用 cloak profile
docker-compose --profile cloak up -d cloakserve

# 验证
curl http://127.0.0.1:9222/json/version
```

### 3.2 worker 开 CLOAK

`docker-compose.yml` 里 crawler-worker 环境变量区取消注释:

```yaml
environment:
  - ANTI_CRAWL_STEALTH_MODE=CLOAK
  - ANTI_CRAWL_CLOAK_CDP_URL=http://127.0.0.1:9222
```

重起 worker:

```bash
docker-compose up -d crawler-worker
```

### 3.3 回滚

`stealth-mode` 改回 `SELF` 或删掉环境变量,重启 worker 即可。**秒回滚**。

## 4. 效果对比测试建议

跑同样的同花顺目标 URL,对比两组:

| 指标 | SELF(现状) | CLOAK |
|---|---|---|
| 抓取成功率 | | |
| 被封/被挑战比例 | | |
| 平均耗时 | | |
| FPJS / CreepJS 检测结果 | | |

测 FPJS 可在同花顺任务里临时把 `url` 换成 `https://bot.incolumitas.com/` 或 `https://fingerprintjs.github.io/fingerprintjs/`,看 raw 输出。

## 5. 常见问题

- **Mac 上报 "App is damaged"**: `xattr -cr ~/.cloakbrowser/chromium-*/Chromium.app`
- **端口被占**: 改 `cloak-local-port` 或先 `lsof -i :9222` 看谁占了
- **cloakserve 起不来**: 手动 `python3 scripts/cloak_serve.py` 看报错;常见是 binary 下载被墙(设 `CLOAKBROWSER_DOWNLOAD_URL` 或用镜像)
- **东财策略受影响吗**: **不受影响**。东财走 OkHttp,不动。

## 6. 后续可选

- 给 `BrowserContextFactory` 加 `geoip=True` 风格的时区匹配(CLOAK 模式已自动处理)
- 给 `TonghuashunBrowserStrategy` 的 `scrollUntilStable` 在 CLOAK 模式下用 `humanize` 替代
- 生产 Pro 订阅 + 多并发 session
