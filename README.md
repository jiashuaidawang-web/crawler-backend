# 股票分布式爬虫底座 crawler-backend

> 方法论来源：《顿悟股道》无门问禅（8 个 skill）。字段口径以 `schema-opengauss.sql` PART A 原始层为准。

## 里程碑状态

- **M1（骨架）** ✅ 已完成：SourceStrategy 接口、CrawlContext/Result/Task、StrategyFactory、AntiCrawlConfig 雏形、worker 认领循环 ClaimLoop、DedupWriter、各类管理表。
- **M2（两个数据源策略做深 + 分页/滚动解析 + 反爬配置增强并接线）** ✅ **已完成**。
- **M3（调度接入 + 批量程序化种子 + 僵尸回收）** ✅ **已完成**：XXL-JOB executor 挂 admin（端口 9999），`dailyCloseSeed` / `historyBackfill` / `retryScan` 三件套 + 手动 REST 触发；种子幂等（unique_key ON CONFLICT DO NOTHING）；`idx_ct_started` 索引支持僵尸回收扫描。

---

## 代理配置（实测 2026-08-04）

> **核心经验：OkHttp 必须用 `interceptor` 主动添加 `Proxy-Authorization` 头，不能用 `authenticator` 被动等 407！**

### 青果短效代理（当前使用）

| 参数 | 值 |
|---|---|
| 提取 API | `https://share.proxy.qg.net/get?key=8XMUHNWJ&num=1&area=&isp=0&format=json&distinct=true` |
| 单价 | 0.0027 元/IP |
| 复活周期 | 1 分钟 |
| 成功率 | ~50%（实测 10 次 5 次成功，一把就成） |
| 返回格式 | `{"code":"SUCCESS","data":[{"server":"ip:port"}]}` |

**application.yml（admin + worker 统一）：**
```yaml
proxy:
  qg:
    api-key: D7A19F5D
    password: EC00F1DB9AAC
```

### 关键教训：OkHttp 代理认证

**❌ 错误方式（成功率 0%）：**
```java
builder.authenticator(new Authenticator() {
    public Request authenticate(Route route, Response response) {
        // 收到 407 后才触发 → 某些代理不触发回调 → 永远 407
    }
});
```

**✅ 正确方式（成功率 ~50%）：**
```java
final String credential = Credentials.basic(username, password);
builder.addInterceptor(chain -> {
    Request request = chain.request().newBuilder()
        .header("Proxy-Authorization", credential)  // 第一请求就带认证
        .build();
    return chain.proceed(request);
});
```

**诊断方法：** 5 个不同 IP/端口/省份的代理全部毫秒级返回 407 → 请求到达了代理但没带认证头。

### 其他代理（备用）

| 代理 | 单价 | 成功率 | 说明 |
|---|---|---|---|
| 巨量(juliangip) | 0.003 元/IP | 40% | `auth_type=2` 用户名密码模式 |
| 快代理(kuaidaili) | 0.015 元/IP | 20% | 账号密码鉴权 |

---

## M2 新增 / 改写类清单

### strategy 层（东财）
| 类 | 动作 | 说明 |
|---|---|---|
| `eastmoney/EastmoneyFieldMap` | 新增 | f 码 → schema 列名静态映射；`toTsCode(f12,f13)` 按市场码补 `.SH/.SZ` 后缀 |
| `eastmoney/EastmoneyEndpoints` | 新增 | 按 taskType 路由端点表（`EndpointSpec` + `buildUrl`）；覆盖 9 个 taskType，其余抛 `UnsupportedOperationException` |
| `eastmoney/EastmoneyClient` | 新增 | OkHttp 封装：随机 UA + Referer；按 proxy 字符串缓存 OkHttpClient；非 2xx 抛 RuntimeException |
| `eastmoney/EastmoneyPageIterator` | 新增 | 实现 `PageIterator`（clist 已知总页数分页；池/明细用「返回空即停」） |
| `eastmoney/EastmoneyApiStrategy` | 重写 | 按 taskType 路由、分页、四类解析器（clist/kline/ztpool/datacenter）、UA/代理/限速接线、trade_date 必带 |

### strategy 层（同花顺）
| 类 | 动作 | 说明 |
|---|---|---|
| `tonghuashun/StealthSpec` | 新增 | stealth 配置（UA/viewport/locale/timezone 随机化）、`randomize()` |
| `tonghuashun/BrowserContextFactory` | 新增 | 建带反爬上下文：随机指纹、stealth init script、代理、Cookie 载入/保存 |
| `tonghuashun/BrowserPool` | 新增 | 常驻单例 Browser 对象池（懒初始化、JVM 关闭钩子、closeAll） |
| `tonghuashun/TonghuashunBrowserStrategy` | 重写 | 导航→等待→（可选滚动到底稳定）→（可选结构化抽取）；UA/代理/限速接线 |

### core / worker 配置与接线
| 类 | 动作 | 说明 |
|---|---|---|
| `core/config/AntiCrawlConfig` | **新增（接口）** | 策略需要的反爬访问方法。**为解决 worker↔strategy 的 Maven 模块循环依赖而放在 core 层**，两模块共用，无需 strategy 依赖 worker |
| `worker/config/AntiCrawlConfig` | 重写 | 实现 core 接口；新增 `perSourceProxies`/`stealthEnabled`/`cookieDir`/`browserArgs`/`proxyRotation` 与 `getProxyFor(SourceType)`；保留原 uaPool/proxyList/proxyEnabled/rateLimitPerSec |
| `worker/config/StrategyFactoryConfig` | 重写 | 注入 `AntiCrawlConfig` + `BrowserPool` 装配 `StrategyFactory`（手动 new，无 @Component） |

> 说明：`ClaimLoop` 仍 import `worker.config.AntiCrawlConfig`（未改动），因 core 接口与 worker 实现解耦，故无需触碰它即可破除循环依赖。

---

## 东财字段 ↔ schema 列 速查表

### clist / kline 通用 f 码投影
| f 码 | schema 列 | 备注 |
|---|---|---|
| f12 | ts_code | 需 `toTsCode(f12,f13)` 按 f13 补后缀 |
| f13 | （内部市场码） | 不直接落库 |
| f14 | name | 按 taskType 决定 ts_code/board_code 的 name 列 |
| f2 | close | |
| f3 | pct_chg | |
| f15/f16/f17/f18 | high/low/open/pre_close | |
| f5 | vol | 成交量(手) |
| f6 | amount | 成交额(元) |
| f62 | main_net | 主力净流入 |
| f66/f72/f78/f84 | super_big/big_net/mid_net/small_net | 超大单/大单/中单/小单 |
| f184 | turnover | 换手率% |
| f104/f105 | up_count/down_count | 板块涨跌家数 |

### 涨停/跌停/炸板池（push2ex `data.pool`，字段名直接用）
| 推送字段 | schema 列 | 备注 |
|---|---|---|
| c | ts_code | 按 m 补 `.SH/.SZ` |
| n | stock_name | |
| zdp | pct_chg | |
| lbc | board_pos | 连板数 |
| ztsj | open_time | HH:MM:SS |
| kbc | open_times | |
| ztjg | close | 涨停价（近似 close） |
| tdzs | reason | 题材/涨停原因 |
| hycode | board_code | TODO M6：若缺 BK 前缀需规范化 |
| hymc | board_name | |
| （派生） | limit_type / limit_style / is_first / is_continuous | 见下「TODO M6」 |

### 龙虎榜（datacenter，大写列名）
| 大写列 | schema 列 |
|---|---|
| SECURITY_CODE | ts_code（按前缀启发式补后缀，TODO M6 核对） |
| SECURITY_NAME_ABBR | stock_name |
| EXPLAIN | reason |
| BUY_AMOUNT / SELL_AMOUNT / NET_BUY_AMOUNT | total_buy / total_sell / net_buy |
| SEAT_NAME / SEAT_TYPE / BUY / SELL | dt_detail.seat_name / seat_type / buy / sell |

### kline（push2his `data.klines` 逗号分隔）
`f51→trade_date, f52→open, f53→close, f54→high, f55→low, f56→vol, f57→amount, f59→pct_chg, f61→turnover`；`pre_close` 用上一行 close 推算（遍历时记录）。

---

## 同花顺 stealth / 代理 / 滚动 / 结构化抽取 落地情况

- **stealth**：`BrowserContextFactory.STEALTH_JS` 覆盖 `navigator.webdriver`、伪造 `plugins/languages/platform`、`window.chrome`；上下文随机 UA/viewport/locale/timezone。属基础 stealth，能过多数基础检测，非指纹级。
- **代理**：`AntiCrawlConfig.getProxyFor(SourceType.TONGHUASHUN)` 按 `proxyRotation`（RANDOM/ROUND_ROBIN）取 perSource 代理；`Browser.NewContextOptions.setProxy(...)` 注入。
- **Cookie 登录态**：`cookieDir/<host>.json` 存在则 `addCookies` 载入；`saveCookies` 辅助持久化。
- **滚动到底稳定**：`scrollUntilStable` 循环 `mouse.wheel` + 间隔，连续两次 `scrollHeight` 不变即停（最多 20 次）。
- **结构化抽取**：`extractRows(page, selector, cols)` 按 `params.extract.selector` + `cols`（目标列→子选择器/数字索引）抽取文本，每行注入 `trade_date`。同花顺具体 DOM 选择器 **需 M6 端到端实测配置**（本类提供通用框架 + 注入点）。

---

## ⚠️ TODO M6 待端到端实测校准的字段（已明确标 TODO，未编造）

1. **board_daily.amount** —— 东财 clist 无直接字段，暂置 `NULL`（需另取或下游聚合）。
2. **board_daily.limit_up_count** —— 东财 clist 无直接字段，置 `NULL`；下游用 `stock_board_rel × limit_pool` 聚合计算。
3. **limit_pool.limit_style** —— 当前为近似判定（`kbc==0 && ztsj=="09:30:00"` → `一字`，否则 `换手`）；炸板/烂板/T字 细分需下游结合 `open_times/last_time` 细化。
4. **dt_detail.is_famous** —— 需维护知名游资名单，当前置 `0`。
5. **dragon_tiger / dt_detail 的 ts_code 后缀** —— datacenter 响应无显式市场字段，`tsCodeFromRaw` 按代码前缀（6→.SH / 0,3→.SZ / 8,4→.BJ）启发式补后缀，TODO M6 核对真实响应字段。
6. **northbound_flow 端点** —— 未实现（`EastmoneyEndpoints.get` 对非覆盖 taskType 抛 `UnsupportedOperationException`），留待 M6 接入北向接口。
7. **同花顺 DOM 选择器** —— `waitSelector` / `extract.selector` / `extract.cols` 依赖 M6 实测页面结构。
8. **STOCK_WEEKLY 行** —— kline 解析对日/周线套用同一投影，会带上 `pct_chg/turnover/pre_close` 等 `stock_weekly` 表没有的列；下游 DedupWriter 应按目标表选列（或 M6 对周线做列裁剪）。

---

## 编译状态与风险点

- **全量编译通过**：`mvn -DskipTests compile` → `BUILD SUCCESS`（crawler-core / strategy / persistence / worker / admin 五个模块全部编译，共 53 个类）。需联网首次拉取依赖（Spring Boot / MyBatis-Plus / OkHttp 4.12 / Playwright 1.40 / Jackson / Lombok）。
- **编译期已修掉的 API 偏差（与 Playwright 1.40.0 / OkHttp 4.12 真实签名对齐）**：
  1. `okhttp3.Proxy` 不存在 → 代理类型改用 `java.net.Proxy`（OkHttp 的 `proxy()` 接收 `java.net.Proxy`）。
  2. Playwright `Proxy` / `Cookie` 在 `com.microsoft.playwright.options.*` 包（非 `com.microsoft.playwright.Proxy`、也非 `BrowserContext.Cookie`）→ 已改为 `options.Proxy` / `options.Cookie`。
  3. `Cookie` 的 SameSite 枚举类名为 `options.SameSiteAttribute`（非 `Cookie.SameSite`）。
  4. `NewContextOptions.setViewport(int,int)` 不存在 → 改用 `setViewportSize(int,int)`。
  5. `ElementHandle.children()` 不存在 → 改用 `querySelectorAll(":scope > *")`。
  6. `options.Proxy` 构造器需传 server 字符串：`new Proxy(server)`。
- **运行时注意**：Playwright 首次运行需联网下载 Chromium（`playwright install chromium`，或由镜像预置）；同花顺策略需本地 Chromium + 代理。OkHttp 代理按 `Proxy.Type.HTTP/SOCKS` 解析 `host:port` 或 `scheme://host:port`。
- 代码层风险（非编译）：kline 对周线未做列裁剪（见 TODO 8）；clist 分页用东财 `pages`，池/明细用「返回空即停」+ 50 页安全上限；字段口径中 TODO M6 项仍待端到端实测校准。

---

## M3 调度与种子生成（XXL-JOB 接入 + 批量种子 + 僵尸回收）

> 说明：计划文档 `01-项目计划.md` 中 M3=「分布式内核」、M4=「调度+校验+告警」。本工程的用户口径 **M3 = 调度接入**，即计划文档里的 M4 内容，已在此落地完成（计划文档里程碑行已标注）。

### 架构落点
- **XXL-JOB executor 挂在 crawler-admin 模块**（控制面/管理面），端口 `9999`（executor port）。worker 只认领、不调度。admin 已依赖 crawler-persistence 与 crawler-core。
- 核心逻辑写成普通 `@Service`（`SeedGenerator` / `RetryScanService`），再包成 `@XxlJob` handler（`XxlJobHandlers`）；**同时提供手动 REST**（`JobController`），使系统在未部署 xxl-job-admin 时也能跑（M6 测试用）。
- 未部署 xxl-job-admin 时，executor 启动会打连接错误日志，但**不影响 admin 应用启动**，手动 REST 仍可触发。

### 三个 Job（XxlJobHandlers）
| Job handler | 建议调度 | param | 动作 |
|---|---|---|---|
| `dailyCloseSeed` | 每日 16:30（收盘后） | `date=2024-01-02&source=1`（缺省 date=今天、source=1） | 调 `SeedGenerator.dailySeed` |
| `historyBackfill` | 一次性回填 | `start=&end=&source=&types=`（types 逗号分隔，缺省全部） | 调 `SeedGenerator.backfill` |
| `retryScan` | 每 5–10 分钟 | `timeoutMin=`（缺省 15） | 先 `reclaimZombies` 再 `promoteExhausted`，返回 `"reclaimed=N promoted=M"` |

### 手动 REST（无需 xxl-job-admin）
- `POST /api/job/daily-seed?date=&source=` → `{"inserted":N}`
- `POST /api/job/backfill?start=&end=&source=&types=` → `{"inserted":N}`
- `POST /api/job/retry-scan?timeoutMin=` → `{"reclaimed":N,"promoted":M}`

### 种子生成要点（SeedGenerator / TaskTypeCatalog）
- **可种子类型目录**集中在 `TaskTypeCatalog.ALL`：市场级（LIMIT_POOL / BOARD_DAILY / MAIN_FUND_STOCK / MAIN_FUND_BOARD / DRAGON_TIGER / STRONG_POOL）与逐券级（STOCK_DAILY / STOCK_WEEKLY / INDEX_DAILY）。
- **LIMIT_POOL 拆子任务**：每日拆成 `LIMIT_UP` / `LIMIT_DOWN` / `LIMIT_ZHABAN` 三个子任务（params 含 `limitType`，unique_key 含 limitType 区分）。
- **幂等**：`unique_key` UNIQUE 约束 + `ON CONFLICT (unique_key) DO NOTHING`；MyBatis 冲突忽略时返回 0，据此统计「本次实际新插入条数」。重复 seed 不会重复入库。
- **量校验口径**：市场级 `expected_count=NULL` → **不做 VOLUME_DEVIATION**（数量波动大，避免误报）；逐券 `expected_count=1` → **做行级量校验**。
- **universe 文件**：`classpath:universe/stocks.json`（tsCode 数组）与 `classpath:universe/indices.json`（指数代码数组）。当前为**占位空数组 `[]`**。**把这两个文件换成你的全市场股票/指数列表即可启用逐券回填**（STOCK_DAILY / STOCK_WEEKLY / INDEX_DAILY）；文件缺失/空时返回空列表并 warn，逐券类型跳过，**不影响市场级任务**。

### ⚠️ TODO（已明确标 TODO，未编造）
1. **DRAGON_TIGER_DETAIL 串联**：`SeedGenerator.seedDragonTigerDetails(date, codes)` 钩子已就位，但 M3 未自动串联——需先爬完 `DRAGON_TIGER` 拿到代码列表再调用，留 TODO。
2. **trade_calendar 交易日过滤**：`backfill` 逐**自然日**遍历 `[start,end]`，**不过滤交易日**。运营建议只回填交易日；后续接 `trade_calendar` 表后再过滤，留 TODO。
3. **逐券 backfill 量大警告**：全市场逐券 × 日期区间会产生海量任务。建议先跑市场级（`daily-seed` / 不传 types），逐券回填分批、错峰执行。

### schema 微调（PART D）
- 新增 `CREATE INDEX idx_ct_started ON crawl_task(started_at);` 支持 retryScan 按 `started_at` 扫描僵尸任务。库已建则执行对应 `ALTER`/`CREATE INDEX`（见 schema 注释）。



# 代理相关的
- worker 获取代理：GET http://124.223.220.245:8088/proxy/get
- worker 归还标记：POST http://124.223.220.245:8088/proxy/report
- 查统计：GET http://124.223.220.245:8088/proxy/stats
- 手动强制抓取：本地 cd proxy-pool && python3 sync_to_server.py --force