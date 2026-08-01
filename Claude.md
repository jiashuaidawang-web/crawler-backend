项目概览：crawler-backend（《顿悟股道》股票数据分布式爬虫底座）

一个面向 A 股市场的分布式数据采集系统，遵循"无门问禅"8 个 skill 方法论，字段口径对齐 schema-opengauss.sql（openGauss/PostgreSQL）。当前处于 M1–M3 均已完成、M6（端到端实测校准）待做 的状态。

整体是 多模块 Maven + Spring Boot 3.2.5 + Java 21 + MyBatis-Plus 3.5.5，两个可执行进程：

┌────────┬────────────────┬──────┬────────────────────────────────────────────┐
│  进程  │      模块      │ 端口 │                    职责                    │
├────────┼────────────────┼──────┼────────────────────────────────────────────┤
│ worker │ crawler-worker │ 8080 │ 认领任务 → 执行策略 → 落库 → 校验          │
├────────┼────────────────┼──────┼────────────────────────────────────────────┤
│ admin  │ crawler-admin  │ 8081 │ 调度 / 种子 / XXL-JOB executor / 监控 REST │
└────────┴────────────────┴──────┴────────────────────────────────────────────┘

  ---
一、模块分层与职责（5 个模块）

1. crawler-core — 领域内核（纯 POJO + 接口）

- 领域模型：CrawlTask（任务）、CrawlContext（执行上下文）、CrawlResult（抓取结果）
- 枚举：SourceType（TONGHUASHUN=0 / EASTMONEY=1 / OTHER=2，与 DB data_source SMALLINT 严格对应）、TaskStatus（PENDING→CLAIMED→SUCCESS/FAILED/RETRY/DEAD 状态机）
- 策略 SPI：SourceStrategy 接口（supports(source) + fetch(ctx)）、StrategyFactory（按 SourceType 路由）
- 重试策略：RetryPolicy 抽象 + ExponentialBackoffRetry（指数退避 delay = base × 2^attempt，有 cap）
- 反爬接口：AntiCrawlConfig 接口（放在 core 层，专门为了破除 worker↔strategy 的 Maven 循环依赖）
- 工具：RateLimiter（自实现令牌桶，阻塞/非阻塞两种获取）、JsonCheckpoint（checkpoint Map ⇄ JSON 互转，用于续传）

2. crawler-strategy — 数据源策略层（OkHttp + Playwright）

两种抓取策略：

东方财富 HTTP/JSON API 策略（SourceType.EASTMONEY）：
- EastmoneyEndpoints：9 个 taskType 的端点路由表（EndpointSpec），覆盖 LIMIT_POOL / STOCK_DAILY / STOCK_WEEKLY / INDEX_DAILY / BOARD_DAILY / MAIN_FUND_STOCK / MAIN_FUND_BOARD / DRAGON_TIGER /
  DRAGON_TIGER_DETAIL；未覆盖的抛 UnsupportedOperationException。含 4 种解析器类型（CLIST/KLINE/ZT_POOL/DATACENTER）
- EastmoneyClient：OkHttp 封装，随机 UA + Referer、按代理字符串缓存 OkHttpClient、非 2xx 抛错
- EastmoneyFieldMap：f 码 → schema 列名静态映射（f12→ts_code、f62→main_net…）+ toTsCode(f12,f13) 按市场码补 .SH/.SZ 后缀
- EastmoneyApiStrategy：按 taskType 路由、分页、四类解析器（clist 用 pages 翻页；池/Datacenter 用「返回空即停」50 页安全上限；kline 按 lmt 一次取足）、UA/代理/令牌桶限速接线、每行强制带 trade_date（分区键必需）
- 字段映射严格对齐 PART A 原始层（个股日周线、板块、主力资金流、龙虎榜）

同花顺浏览器策略（SourceType.TONGHUASHUN）：
- BrowserPool：常驻单例无头 Chromium（懒初始化 + JVM 关闭钩子），高并发可扩展为多实例
- StealthSpec：浏览器指纹随机化（UA/viewport/locale/timezone），每次会话换一组
- BrowserContextFactory：建带反爬上下文 —— 随机指纹 + stealth init script（覆盖 navigator.webdriver、伪造 plugins/languages/chrome）+ perSource 代理 + Cookie 登录态载入/保存
- TonghuashunBrowserStrategy：导航→等待选择器→可选滚动到底稳定（连续两次 scrollHeight 不变即停，最多 20 次）→可选结构化抽取（extractRows 按 selector + cols 抽文本）。具体 DOM 选择器需 M6 实测配置（提供通用框架 +
  注入点）

分页抽象：PageIterator 接口 + SimplePageIterator / EastmoneyPageIterator（页码递增型）

3. crawler-persistence — 持久层（MyBatis-Plus + openGauss/PG）

- 实体：CrawlTask（@TableName("crawl_task")，带 SourceTypeTypeHandler 做 enum⇄SMALLINT 映射）、CrawlNode、CrawlLog、CrawlAlert、LimitPool（示例原始表）
- 核心认领 SQL（CrawlTaskMapper.claim）：UPDATE ... WHERE task_id IN (SELECT ... FOR UPDATE SKIP LOCKED) RETURNING *，多节点不重复认领
- ClaimService：claim / complete / fail（按 willRetry + 已重试次数 → RETRY(next_retry_at) / DEAD / FAILED）
- DedupWriter：去重/溯源写入。优先级裁决：新来源代码 > 已存在代码才覆写（东财优先 daily/board，同花顺优先 board_rel）；LimitPool 演示幂等 upsert（ON CONFLICT (ts_code, trade_date) DO UPDATE）
- VolumeValidator：数据量校验，expected_count vs 实际 rowCount，偏差 > 20% 写 crawl_alert（VOLUME_DEVIATION）
- 幂等种子写入：insertIfAbsent / batchInsertIfAbsent（ON CONFLICT (unique_key) DO NOTHING，冲突返回 0 据此统计新插入条数）
- 僵尸回收 SQL：reclaimZombies（超时 CLAIMED 重置 PENDING）、promoteExhausted（retry_count≥max_retry 置 DEAD）

4. crawler-worker — 爬虫节点进程

- ClaimLoop（@Scheduled 主循环）：claim(batch) → StrategyFactory.execute → DedupWriter 落库 → VolumeValidator 校验 → complete/fail → 每步写 crawl_log
- CrawlNodeHeartbeat：每 30s upsert crawl_node（节点注册与心跳）
- StrategyFactoryConfig：装配 StrategyFactory（手动 new 两个策略，strategy 模块不依赖 Spring）
- worker.config.AntiCrawlConfig：实现 core 接口，对应 anti-crawl.* 配置（UA 池、全局/按 source 代理池、限速、stealth、Cookie 目录、浏览器参数、代理轮换 RANDOM/ROUND_ROBIN）

5. crawler-admin — 控制面进程（调度 + 种子 + 监控）

- 种子生成器 SeedGenerator：
    - dailySeed(date, source)：市场级（LIMIT_POOL 拆成 limit_up/down/zhaban 三个子任务）+ 逐券（读 classpath:universe/stocks.json、indices.json）
    - backfill(start, end, source, types)：历史区间回填（逐自然日遍历，不过滤交易日，留 TODO）
    - 幂等 + 量校验口径：市场级 expected_count=null（不做 VOLUME_DEVIATION），逐券 expected_count=1（行级校验）
- TaskTypeCatalog：可种子类型集中目录（9 种 TaskSpec）+ 唯一键构造（市场级 taskType|source|date，逐券 taskType|source|code|date）+ params JSON 构造
- StockUniverseProvider：读 classpath:universe/*.json（当前是占位空数组 []，换成全市场列表即启用逐券回填）
- XXL-JOB 三件套（XxlJobHandlers）：dailyCloseSeed（16:30）/ historyBackfill（一次性）/ retryScan（5–10 分钟）
- 手动 REST（JobController，无需 xxl-job-admin）：POST /api/job/{daily-seed|backfill|retry-scan}
- SeedController：POST /api/crawl/seed 手工下发单个 crawl_task
- 监控 REST（MonitorController）：GET /api/crawl/stats（状态计数 + 成功率，可按 source/node 分组）、/api/crawl/alerts、/api/crawl/nodes

  ---

二、已实现的能力清单（对应 8 个 skill）

┌───────────────────────────────────────┬───────────────────────────────────────────────┬────────────────────┐
│                 能力                  │                     落点                      │        状态        │
├───────────────────────────────────────┼───────────────────────────────────────────────┼────────────────────┤
│ ① 策略 SPI + 多数据源路由             │ SourceStrategy / StrategyFactory     东     东  │ ✅ 东财+同花顺      │
├───────────────────────────────────────┼───────────────────────────────────────────────┼────────────────────┤
│ ② 反爬（UA/代理/限速/stealth/Cookie） │ AntiCrawlConfig + worker/strategy 实现        │ ✅                  │
├───────────────────────────────────────┼───────────────────────────────────────────────┼────────────────────┤
│ ③ 分页/滚动/解析                      │ PageIterator / scrollUntilStable / 四类解析器 │ ✅                  │
├───────────────────────────────────────┼───────────────────────────────────────────────┼────────────────────┤
│ ④ 去重 + 溯源写入                     │ DedupWriter（优先级裁决）                     │ ✅  limit_pool 示例 │
├───────────────────────────────────────┼───────────────────────────────────────────────┼────────────────────┤
│ ⑤ 断点续传                            │ JsonCheckpoint / crawl_task.checkpoint        │ ✅  框架就位        │
├───────────────────────────────────────┼───────────────────────────────────────────────┼────────────────────┤
│ ⑥ 节点心跳 + 监控                     │ CrawlNodeHeartbeat / MonitorService + REST    │ ✅                  │
├───────────────────────────────────────┼───────────────────────────────────────────────┼────────────────────┤
│ ⑦ 数据量校验告警                      │ VolumeValidator / crawl_alert                 │ ✅                  │
├───────────────────────────────────────┼───────────────────────────────────────────────┼────────────────────┤
│ ⑧ 双源优先级覆写                      │ DedupWriter.precedence                        │ ✅                  │
├───────────────────────────────────────┼───────────────────────────────────────────────┼────────────────────┤
│ ⑨ 任务认领 + 重试 + 僵尸回收          │ ClaimService / ClaimLoop / RetryScanService   │ ✅                  │
├───────────────────────────────────────┼───────────────────────────────────────────────┼────────────────────┤
│ ⑩ 幂等种子 + 调度                     │ SeedGenerator / XXL-JOB / 手动 REST           │ ✅                  │
├───────────────────────────────────────┼───────────────────────────────────────────────┼────────────────────┤
│ ⑪ 全链路日志                          │ crawl_log 表                                  │ ✅                  │
├───────────────────────────────────────┼───────────────────────────────────────────────┼────────────────────┤
│ ⑫ 分布式 SKIP LOCKED 认领             │ CrawlTaskMapper.claim                         │ ✅                  │
└───────────────────────────────────────┴───────────────────────────────────────────────┴────────────────────┘

  ---
三、数据覆盖范围（东财 9 个 taskType 全实现）

┌──────────────────────────────────────┬────────────┬────────────────────────────┐
│               taskType               │   解析器   │    目标原始表（PART A）    │
├──────────────────────────────────────┼────────────┼────────────────────────────┤
│ STOCK_DAILY / STOCK_WEEKLY           │ KLINE      │ stock_daily / stock_weekly │
├──────────────────────────────────────┼────────────┼────────────────────────────┤
│ INDEX_DAILY                          │ KLINE      │ index_daily                │
├──────────────────────────────────────┼────────────┼────────────────────────────┤
│ BOARD_DAILY                          │ CLIST      │ board_daily                │
├──────────────────────────────────────┼────────────┼────────────────────────────┤
│ MAIN_FUND_STOCK / MAIN_FUND_BOARD    │ CLIST      │ main_fund_flow             │
├──────────────────────────────────────┼────────────┼────────────────────────────┤
│ DRAGON_TIGER / DRAGON_TIGER_DETAIL   │ DATACENTER │ dragon_tiger / dt_detail   │
├──────────────────────────────────────┼────────────┼────────────────────────────┤
│ LIMIT_POOL（→ limit_up/down/zhaban） │ ZT_POOL    │ limit_pool                 │
└──────────────────────────────────────┴────────────┴────────────────────────────┘

  ---

README 里明确标注了 8 项 TODO M6，都是"未编造、等实测"的：
1. board_daily.amount / limit_up_count 东财 clist 无直接字段，暂 NULL
2. limit_style 仅近似判定（一字/换手），炸板/烂板细分待下游
3. dt_detail.is_famous 需维护游资名单，当前置 0
4. 龙虎榜 ts_code 后缀按代码前缀启发式补，待核对真实字段
5. northbound_flow 端点未实现
6. 同花顺 DOM 选择器需 M6 实测配置
7. STOCK_WEEKLY 行未做列裁剪（带了周线表没有的列）
8. DRAGON_TIGER_DETAIL 串联未自动完成

运行时前提：Playwright 首次需 playwright install chromium；同花顺需本地 Chromium + 代理；universe JSON 换成真实全市场列表才启用逐券回填。