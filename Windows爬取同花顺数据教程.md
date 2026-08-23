# 从零在 Windows 搭建同花顺股票数据爬取 + 入库系统

> 一篇手把手的教学，覆盖环境安装、模拟器抓包、证书配置、反爬浏览器、数据库建表、爬虫脚本、Java 后端构建、增量入库的完整链路。
>
> **目标**：每天自动爬取同花顺「个股异动」和「股票-板块关联关系」数据，增量写入 ClickHouse。

---

## 目录

1. [系统架构概览](#1-系统架构概览)
2. [环境准备（基础）](#2-环境准备基础)
   - 2.1 Python 环境
   - 2.2 JDK 21 + Maven
   - 2.3 ClickHouse
3. [环境准备（同花顺联通）](#3-环境准备同花顺联通)
   - 3.1 雷电模拟器安装
   - 3.2 mitmproxy 代理 + 证书安装
   - 3.3 同花顺 App 登录与 Cookie 捕获
   - 3.4 CloakBrowser 反爬浏览器
4. [数据库建表](#4-数据库建表)
5. [爬虫脚本](#5-爬虫脚本)
   - 5.1 个股异动爬虫
   - 5.2 股票-板块关联爬虫
6. [Java 后端服务](#6-java-后端服务)
   - 6.1 项目结构说明
   - 6.2 双数据源配置
   - 6.3 构建与启动
   - 6.4 入库 API
7. [每日操作流程](#7-每日操作流程)
   - 7.1 异动数据（全量爬取 → 增量入库）
   - 7.2 板块关联（备份 → 爬取 → 对比 → 增量入库）
8. [踩坑记录与常见问题](#8-踩坑记录与常见问题)
9. [附录：一键启动脚本](#9-附录一键启动脚本)

---

## 1. 系统架构概览

```
┌─────────────────────────────────────────────────────────────────────┐
│  Windows 本机                                                        │
│                                                                     │
│  ┌─────────────────────┐     ┌─────────────────────────────────┐   │
│  │  雷电模拟器 + 同花顺  │     │  CloakBrowser (隐身浏览器)       │   │
│  │  + mitmproxy 抓包    │     │  - 绕过 TLS 指纹/滑块检测        │   │
│  │  → 获取 Cookie      │     │  - CDP 9222 端口                 │   │
│  └─────────┬───────────┘     └──────────┬──────────────────────┘   │
│            │                            │                           │
│            ▼                            ▼                           │
│  ┌─────────────────────────────────────────────────────────────┐   │
│  │              Cookie 文件 (cookies/*.json)                    │   │
│  └────────────────────────────┬────────────────────────────────┘   │
│                               │                                     │
│  ┌──────────────┐    ┌────────┴───────┐                             │
│  │ ths_anomaly  │    │ stock_board    │   ← Python 爬虫             │
│  │ _crawler.py  │    │ _ref.py        │                             │
│  └──────┬───────┘    └────────┬───────┘                             │
│         │                     │                                     │
│         ▼                     ▼                                     │
│  ┌──────────────┐    ┌──────────────┐                               │
│  │ ths_anomaly  │    │ stockBoard   │   ← JSON 文件                 │
│  │ _data/stocks │    │ Ref/         │                               │
│  └──────┬───────┘    └──────┬───────┘                               │
│         │                   │                                       │
│         ▼                   ▼                                       │
│  ┌─────────────────────────────────────┐                            │
│  │     Spring Boot (crawler-admin)      │                            │
│  │     POST /api/init/stock-anomaly     │                            │
│  │     POST /api/init/stock-board-rel   │                            │
│  └──────────────────┬──────────────────┘                            │
│                     │                                               │
└─────────────────────┼───────────────────────────────────────────────┘
                      │
                      ▼
         ┌────────────────────────┐
         │  ClickHouse (远程服务器) │
         │  stock_anomaly         │
         │  stock_board_rel       │
         └────────────────────────┘
```

**数据流**：雷电模拟器抓包获取 Cookie → Python 爬虫（带 Cookie 请求同花顺 API）→ 桌面 JSON 文件 → Java 接口 → ClickHouse

---

## 2. 环境准备（基础）

### 2.1 Python 环境

**安装 Python 3.10+**（推荐 3.11）

```bash
python --version
# Python 3.11.9
```

**安装依赖包**：

```bash
pip install akshare==1.18.91 requests==2.34.2 pandas==3.0.5
```

| 包 | 用途 |
|---|---|
| `akshare` | 获取 A 股股票列表（约 5500 只） |
| `requests` | HTTP 请求同花顺 API |
| `pandas` | akshare 返回 DataFrame 处理 |

**验证安装**：

```bash
python -c "import akshare as ak; df=ak.stock_info_a_code_name(); print(f'A股数量: {len(df)}')"
# 输出: A股数量: 5547
```

### 2.2 JDK 21 + Maven

> ⚠️ 项目使用 Java 21，**不是** Java 8。本机可以同时装多个 JDK，通过切换 `JAVA_HOME` 使用。

**安装 JDK 21**：

1. 下载 [JDK 21 LTS](https://www.oracle.com/java/technologies/downloads/#java21)
2. 安装到 `D:\Development\Environment\Jdk\jdk-21.0.11`

**安装 Maven**：

1. 下载 [Apache Maven 3.8.x](https://maven.apache.org/download.cgi)
2. 解压到 `D:\Development\Environment\apache-maven-3.8.1`
3. 配置 `settings.xml` 使用阿里云镜像（加速依赖下载）

**验证**：

```bash
# 切换到 JDK 21
set JAVA_HOME=D:\Development\Environment\Jdk\jdk-21.0.11
set PATH=%JAVA_HOME%\bin;%PATH%

java -version
# java version "21.0.11" LTS

mvn -version
# Apache Maven 3.8.1
```

### 2.3 ClickHouse

ClickHouse 通常部署在远程服务器上，本机只需要能通过网络连接即可。

**服务器信息示例**：

```
Host: 100.97.74.45
Port: 8123 (HTTP) / 9000 (Native)
Database: crawler
User: default
Password: pamirs@123
```

**测试连接**（在 Java 应用启动后会自动验证，也可用 curl）：

```bash
curl "http://100.97.74.45:8123/?user=default&password=pamirs@123&query=SELECT+1"
```

---

## 3. 环境准备（同花顺联通）

> ⚠️ 这是最关键的环节。同花顺有较强的反爬机制（TLS 指纹、滑块验证、App 端加密），需要一套组合拳才能稳定抓取。

### 3.1 雷电模拟器安装

**为什么需要模拟器？**

同花顺 PC 网页版有严格的反爬检测（WebDriver 检测、TLS 指纹、行为分析），而移动端 App 端的 API 相对容易抓取。我们在安卓模拟器中运行同花顺 App，通过 mitmproxy 中间人代理抓包，获取 API 请求格式和 Cookie。

**安装步骤**：

1. 下载 [雷电模拟器 9](https://www.ldmnq.com/)
2. 安装到默认路径
3. 启动模拟器，进入安卓桌面

**模拟器配置**：
- Android 9（推荐）
- 分辨率：1280×720
- 开启 Root 权限（设置 → 其他设置 → 开启 Root）
- 开启 ADB 调试（设置 → 其他设置 → 开启 ADB 调试）

**安装同花顺 App**：

1. 在模拟器中打开浏览器，搜索「同花顺」下载 APK
2. 或通过 `adb install` 安装：
   ```bash
   adb install ths_android.apk
   ```
3. 启动同花顺，**登录你的同花顺账号**

### 3.2 mitmproxy 代理 + 证书安装

**原理**：在 Windows 上运行 mitmproxy 作为中间人代理，模拟器所有 HTTP/HTTPS 流量经过它。mitmproxy 动态生成证书解密 HTTPS，我们就能抓到同花顺 API 的请求和响应。

**安装 mitmproxy**：

```bash
pip install mitmproxy
```

**配置模拟器代理**：

```bash
# 查看模拟器 ADB 端口（雷电模拟器默认 5555）
adb devices
# 输出: 127.0.0.1:5555 device

# 设置代理指向 Windows 本机（192.168.3.27 是本机内网 IP，8080 是 mitmproxy 端口）
adb shell settings put global http_proxy 192.168.3.27:8080
```

**启动 mitmproxy**：

```bash
# 启动 mitmproxy，监听 8080 端口
mitmproxy -p 8080

# 或启动 mitmdump（命令行模式，无 UI）
mitmdump -p 8080
```

**安装 SSL 证书到模拟器**：

1. 设置好代理后，在模拟器浏览器访问 `http://mitm.it`
2. 点击 Android → 下载证书
3. 证书文件名为 `c8750f0d.0`（mitmproxy 的 CA 证书）

**将证书安装到系统信任区**（需要 Root）：

```bash
# 推送证书到模拟器
adb push c8750f0d.0 /data/local/tmp/

# 挂载到系统证书目录
adb shell "cp /data/local/tmp/c8750f0d.0 /data/local/tmp/cacerts/"
adb shell "chmod 644 /data/local/tmp/cacerts/c8750f0d.0"
adb shell "chown root:root /data/local/tmp/cacerts/c8750f0d.0"
adb shell "chcon u:object_r:system_security_ca_cert:s0 /data/local/tmp/cacerts/c8750f0d.0"
adb shell "mount --bind /data/local/tmp/cacerts /system/etc/security/cacerts"
```

> 💡 桌面上的 `fix_proxy_cert.bat` 脚本可以一键完成上述证书安装步骤。

**验证**：

```bash
# 在模拟器中打开同花顺 App，操作几下
# mitmproxy 终端应该能看到请求流过
# 如果看不到，检查代理设置和证书
```

### 3.3 同花顺 App 登录与 Cookie 捕获

**通过 mitmproxy 抓包获取 Cookie**：

1. 启动 mitmproxy 后，在模拟器中操作同花顺 App（浏览行情、查看异动等）
2. mitmproxy 会显示所有经过的请求
3. 找到目标 API 请求（如 `flow.10jqka.com.cn/anomaly/v1/history`）
4. 查看请求头中的 Cookie 字段

**更优雅的方式：使用 TonghuashunLogin.java 自动导出 Cookie**

项目内置了 `TonghuashunLogin.java`，可以自动完成登录并导出 Cookie：

```bash
# 编译并运行
set JAVA_HOME=D:\Development\Environment\Jdk\jdk-21.0.11
cd D:\Development\IDEAWorkSpace\Github\new\crawler-backend

# 运行登录工具（使用 Cloak 模式绕过反爬）
STEALTH_MODE=CLOAK java -cp crawler-strategy/target/classes com.dunwugudao.crawler.strategy.tonghuashun.TonghuashunLogin <你的手机号> <你的密码>
```

**Cookie 文件存储位置**：

```
项目根目录/cookies/
├── quote.10jqka.com.cn.json      ← 同花顺行情 Cookie
├── stockpage.10jqka.com.cn.json  ← 个股页 Cookie
├── www.10jqka.com.cn.json        ← 主站 Cookie
├── data.10jqka.com.cn.json       ← 数据中心 Cookie
└── q.10jqka.com.cn.json          ← 其他接口 Cookie
```

**Cookie 有效期**：通常 7-30 天，过期后需要重新登录导出。

### 3.4 CloakBrowser 反爬浏览器

> 对于需要浏览器访问的同花顺页面（如板块详情、龙虎榜等），使用 CloakBrowser 绕过反爬检测。

**CloakBrowser 是什么？**

一个 C++ 源码级隐身浏览器，相比 Playwright/Puppeteer 的自带反检测：
- 完全隐藏 WebDriver 特征
- 随机化浏览器指纹（Canvas、WebGL、AudioContext 等）
- 模拟人类操作轨迹
- 绕过 TLS 指纹检测（JA3/JA4）

**安装 CloakBrowser**：

```bash
pip install cloakbrowser

# 下载 Chromium binary（约 200MB）
python -m cloakbrowser install
```

**启动 CloakBrowser**：

项目提供了启动脚本 `scripts/cloak_serve.py`：

```bash
# 基础启动
python scripts/cloak_serve.py

# 带许可证 key（提升效果，免费申请）
set CLOAKBROWSER_LICENSE_KEY=cb_你的key
python scripts/cloak_serve.py

# 带代理（青果长效 IP）
set CLOAK_PROXY=http://user:pass@proxy:port
python scripts/cloak_serve.py
```

**环境变量**：

| 变量 | 默认值 | 说明 |
|---|---|---|
| `CLOAK_PORT` | 9222 | CDP 调试端口 |
| `CLOAKBROWSER_LICENSE_KEY` | 空 | 许可证 key（空=免费版） |
| `CLOAK_PROXY` | 空 | 代理地址 |
| `CLOAK_HEADLESS` | true | 是否无头模式 |
| `CLOAK_HUMANIZE` | true | 是否模拟人类操作 |
| `CLOAK_FINGERPRINT_SEED` | 空 | 固定指纹 seed（留空=每次随机） |

**免费 License Key**：

到 <https://cloakbrowser.dev/github> 登录获取免费 key，能拿到最新构建（71 补丁）。不拿 key 也能跑，但只用的 v146 公开版（58 补丁）。

**验证 CloakBrowser 是否工作**：

```bash
# 启动后，用 curl 测试 CDP 端口
curl http://127.0.0.1:9222/json/version
# 应该返回浏览器版本信息
```

**CloakBrowser 与 Java 端的集成**：

Java 端通过 Playwright 连接 CloakBrowser 的 CDP 端口：

```java
// 连接 CloakBrowser
Browser browser = playwright.chromium().connectOverCDP("http://127.0.0.1:9222");
```

Java 端的 `CloakServerProcess` 会自动检测并启动 `cloak_serve.py`，无需手动启动。

---

## 4. 数据库建表

在 ClickHouse 中执行以下建表语句：

### 4.1 异动数据表

```sql
-- 文件: schema-stock-anomaly.sql
CREATE TABLE IF NOT EXISTS stock_anomaly ON CLUSTER clickhouse_cluster (
    ts_code         String NOT NULL COMMENT '个股代码(如 600000.SH)',
    anomaly_id      UInt64 NOT NULL COMMENT '同花顺异动唯一ID（去重键）',
    anomaly_date    Date COMMENT '异动日期',
    tag_code        String COMMENT '异动类型编码(如 SHARP_RISE/LIMIT_UP)',
    tag_name        String COMMENT '异动类型中文(大涨/涨停/大跌)',
    reason          String COMMENT '异动原因（原文）',
    keywords        String COMMENT '关键词JSON数组',
    stock_name      String COMMENT '股票名称',
    feature         String COMMENT '原始JSON串（完整异动记录）',
    data_source     UInt8 DEFAULT 0 COMMENT '数据来源：0=同花顺',
    create_date     Date DEFAULT today() COMMENT '入库日期',
    update_date     DateTime DEFAULT now() COMMENT '更新时间'
) ENGINE = ReplacingMergeTree(data_source)
PARTITION BY toYYYYMM(anomaly_date)
ORDER BY (ts_code, anomaly_id)
SETTINGS index_granularity = 8192;
```

**关键设计**：
- `ReplacingMergeTree(data_source)`：按 `data_source` 版本去重，`anomaly_id` 全局唯一天然幂等
- `ORDER BY (ts_code, anomaly_id)`：主键，去重依据
- `PARTITION BY toYYYYMM(anomaly_date)`：按月分区

### 4.2 股票-板块关联表

```sql
-- 来自 clickhouse-schema.sql
CREATE TABLE IF NOT EXISTS stock_board_rel ON CLUSTER clickhouse_cluster (
    ts_code         String NOT NULL,
    board_code      String NOT NULL,
    board_name      Nullable(String),
    stock_name      Nullable(String),
    board_type      Int32 NOT NULL COMMENT '1地域 2行业 3概念',
    is_leader       Nullable(UInt8),
    is_midarm       Nullable(UInt8),
    weight          Nullable(Decimal64(4)),
    effective_date  Nullable(Date),
    data_source     UInt8 NOT NULL DEFAULT 0,
    trade_date      Nullable(Date),
    src_detail      Nullable(String),
    create_date     Nullable(Date),
    update_date     Nullable(DateTime),
    _ver            UInt8 MATERIALIZED data_source
) ENGINE = ReplacingMergeTree(_ver)
PARTITION BY toYYYYMM(effective_date)
ORDER BY (board_code, ts_code, board_type, data_source)
SETTINGS index_granularity = 8192, allow_nullable_key = 1;
```

**关键设计**：
- `ORDER BY (board_code, ts_code, board_type, data_source)`：唯一键是「板块+股票+类型+来源」组合
- `_ver MATERIALIZED data_source`：版本列，用于 ReplacingMergeTree 去重

---

## 5. 爬虫脚本

脚本放在桌面 `C:\Users\Administrator\Desktop\` 下，直接用 Python 运行。

### 5.1 个股异动爬虫

**文件**：`ths_anomaly_crawler.py`

**API**：
```
POST https://flow.10jqka.com.cn/anomaly/v1/history
Body: {"thsHqCode": "股票代码", "marketId": "市场ID", "count": 252}
```

**市场 ID**：
| market_id | 市场 | 代码前缀 |
|---|---|---|
| 17 | 上海 A 股 | 60xxxx |
| 33 | 深圳 A 股 | 00xxxx |
| 48 | 创业板 | 30xxxx |
| 128 | 科创板 | 68xxxx |

**运行命令**：

```bash
# 全量爬取所有 A 股（约 5500 只，耗时 ~2 小时）
python ths_anomaly_crawler.py --all --workers 2 --delay 1.0

# 测试模式（只抓 5 只）
python ths_anomaly_crawler.py --test
```

**参数说明**：
| 参数 | 默认值 | 说明 |
|---|---|---|
| `--all` | false | 全量模式（用 akshare 获取真实股票列表） |
| `--workers` | 2 | 并发线程数（建议 ≤ 3） |
| `--delay` | 1.0 | 请求间隔秒数（建议 ≥ 0.5） |
| `--count` | 252 | 每只股票返回的历史条数 |

**输出**：
- 目录：`Desktop\ths_anomaly_data\stocks\`
- 格式：每只股票一个 JSON 文件（如 `000001.json`）
- 增量逻辑：新记录按 `anomaly_id` 去重合并，新数据在前

**JSON 文件结构**：

```json
{
  "stock_code": "000001",
  "market_id": "33",
  "stock_name": "平安银行",
  "last_update": "2026-08-16T15:45:14",
  "total_count": 4,
  "anomaly_list": [
    {
      "id": 21404354,
      "stockName": "平安银行",
      "date": "2025-04-07",
      "tagCode": "SHARP_FALL",
      "tagName": "大跌",
      "reason": "1、美国"对等关税"阴云笼罩全球股市...",
      "keywordList": ["全球股市大跌"]
    }
  ]
}
```

### 5.2 股票-板块关联爬虫

**文件**：`stock_board_ref.py`

**API**：
```
GET https://basic.10jqka.com.cn/fuyao/f10_stock_index/concept/v1/stock_concept_list
Params: market_id=33, code=000001, locale=zh_CN
```

**运行命令**：

```bash
# 全量爬取
python stock_board_ref.py --all --workers 2 --delay 1.0

# 测试模式
python stock_board_ref.py --test
```

**输出**：
- 目录：`Desktop\stockBoardRef\`
- 格式：每只股票一个 JSON 文件，包含所属的所有概念板块

**JSON 文件结构**：

```json
{
  "stock_code": "000001",
  "market_id": "33",
  "stock_name": "平安银行",
  "board_count": 12,
  "boards": [
    {
      "concept_id": "300750",
      "name": "互联网金融",
      "quote_code": "BK0450",
      "market_id": "33",
      "fit_rank": 1,
      "rise_cnt": 15,
      "fall_cnt": 8
    }
  ]
}
```

---

## 6. Java 后端服务

### 6.1 项目结构

```
crawler-backend/
├── crawler-admin/          ← Spring Boot 启动模块（REST API）
├── crawler-persistence/    ← 数据层（Entity + Mapper + DataSourceConfig）
├── crawler-core/           ← 核心模型
├── crawler-strategy/       ← 爬虫策略
├── crawler-worker/         ← 爬虫工作节点
├── pom.xml                 ← 父 POM
├── schema-stock-anomaly.sql
├── schema-stock-board-rel.sql
├── clickhouse-schema.sql
└── cookies/                ← 同花顺 Cookie 文件
```

### 6.2 双数据源配置

**配置文件**：`crawler-admin/src/main/resources/application.yml`

```yaml
spring:
  datasource:
    # 主数据源：openGauss（操作型表）
    driver-class-name: org.postgresql.Driver
    url: jdbc:postgresql://100.97.74.45:5432/postgres
    username: dbuser
    password: OpenGauss@2026

    # 分析型数据源：ClickHouse
    ch:
      driver-class-name: com.clickhouse.jdbc.ClickHouseDriver
      jdbc-url: jdbc:clickhouse://100.97.74.45:8123/crawler?compress=0&use_server_time_zone=false&rewrite_batch_inserts=true
      username: default
      password: pamirs@123

# 初始化配置（JSON 文件目录）
init:
  stock-anomaly:
    json-dir: C:/Users/Administrator/Desktop/ths_anomaly_data/stocks
  stock-board-rel:
    json-dir: C:/Users/Administrator/Desktop/stockBoardRef
```

### 6.3 双数据源架构说明

> ⚠️ 这是一个容易踩坑的点。

项目使用 **双数据源**（openGauss + ClickHouse），在这种架构下：

- **`@Mapper` 注解不生效**！Spring Boot 的自动扫描发现不了 Mapper
- 所有 Mapper 必须在 `DataSourceConfig` 中**显式注册为 `MapperFactoryBean`**

```java
// DataSourceConfig.java 中的注册方式
@Bean
public MapperFactoryBean<StockAnomalyMapper> stockAnomalyMapper(
        @Qualifier("chSqlSessionFactory") SqlSessionFactory f) throws Exception {
    return newMapper(f, StockAnomalyMapper.class);
}
```

**每个新 Mapper 都需要在这里加一行**，否则启动会报：
```
Parameter 0 of constructor in StockAnomalyInitService required a bean of type 'StockAnomalyMapper' that could not be found.
```

### 6.4 构建与启动

**构建**：

```bash
# 切换到 JDK 21
set JAVA_HOME=D:\Development\Environment\Jdk\jdk-21.0.11
set PATH=%JAVA_HOME%\bin;%PATH%

# 进入项目目录
cd D:\Development\IDEAWorkSpace\Github\new\crawler-backend

# 构建（跳过测试，加快速度）
mvn -q -DskipTests clean package
```

构建成功后，jar 文件位于：
```
crawler-admin/target/crawler-admin-0.1.0.jar
```

**启动**：

```bash
set JAVA_HOME=D:\Development\Environment\Jdk\jdk-21.0.11
set PATH=%JAVA_HOME%\bin;%PATH%

cd D:\Development\IDEAWorkSpace\Github\new\crawler-backend

java -jar crawler-admin/target/crawler-admin-0.1.0.jar
```

启动成功的标志：
```
Started CrawlerAdminApplication in 3.654 seconds
Tomcat started on port 8081 (http)
```

### 6.5 入库 API

| 接口 | 说明 |
|---|---|
| `POST /api/init/stock-anomaly` | 异动数据入库（支持 `?dir=路径` 指定目录） |
| `POST /api/init/stock-board-rel` | 板块关联入库（支持 `?dir=路径` 指定目录） |

**调用示例**：

```bash
# 入库异动数据（默认读取配置的目录）
curl -X POST http://localhost:8081/api/init/stock-anomaly

# 入库异动数据（指定目录）
curl -X POST "http://localhost:8081/api/init/stock-anomaly?dir=C:/Users/Administrator/Desktop/ths_anomaly_data/stocks"

# 入库板块关联
curl -X POST "http://localhost:8081/api/init/stock-board-rel?dir=C:/Users/Administrator/Desktop/stockBoardRef"
```

**返回示例**：

```json
{
  "status": "ok",
  "table": "stock_anomaly",
  "totalFiles": 3159,
  "totalRows": 100393,
  "parseErrors": 0,
  "dataSource": 0,
  "dataSourceLabel": "同花顺",
  "targetDir": "C:/Users/Administrator/Desktop/ths_anomaly_data/stocks"
}
```

---

## 7. 每日操作流程

### 7.1 异动数据（增量入库）

异动数据天然适合增量——每只股票的 `anomaly_id` 是全局唯一的，新记录只会增加不会修改。

```bash
# 第一步：爬取（增量更新 JSON 文件）
cd C:\Users\Administrator\Administrator\Desktop
python ths_anomaly_crawler.py --all --workers 2 --delay 1.0
# 耗时约 2 小时

# 第二步：入库（自动去重，只插入新记录）
curl -X POST http://localhost:8081/api/init/stock-anomaly
```

**增量原理**：
1. 爬虫按 `anomaly_id` 去重合并到 JSON 文件
2. Java 服务解析 JSON 后，先查数据库中已存在的 `anomaly_id`
3. 过滤掉已存在的，只插入新记录

### 7.2 板块关联（文件对比增量）

板块关联的变化频率很低（大部分股票所属板块不变），所以用文件对比的方式更高效。

```bash
cd C:\Users\Administrator\Desktop

# 第一步：备份昨天的数据（重命名目录）
rename stockBoardRef stockBoardRef_yesterday_backup

# 第二步：爬取今天的数据
python stock_board_ref.py --all --workers 2 --delay 1.0
# 耗时约 1.5 小时

# 第三步：对比差异，提取增量
python -c "
import os, json, shutil

today_dir = 'stockBoardRef'
backup_dir = 'stockBoardRef_yesterday_backup'
incr_dir = 'stockBoardRef_incremental'
os.makedirs(incr_dir, exist_ok=True)

today_files = set(f for f in os.listdir(today_dir) if f.endswith('.json'))
backup_files = set(f for f in os.listdir(backup_dir) if f.endswith('.json'))

def get_board_codes(fpath):
    with open(fpath, 'r', encoding='utf-8') as f:
        data = json.load(f)
    return set(b.get('quote_code', '') for b in data.get('boards', []))

# 新增文件
for f in today_files - backup_files:
    shutil.copy2(os.path.join(today_dir, f), os.path.join(incr_dir, f))

# 内容变化的文件（只比较板块集合，忽略每日波动的统计数据）
for f in today_files & backup_files:
    today_codes = get_board_codes(os.path.join(today_dir, f))
    backup_codes = get_board_codes(os.path.join(backup_dir, f))
    if today_codes != backup_codes:
        shutil.copy2(os.path.join(today_dir, f), os.path.join(incr_dir, f))

print(f'增量文件数: {len(os.listdir(incr_dir))}')
"

# 第四步：入库增量
curl -X POST "http://localhost:8081/api/init/stock-board-rel?dir=C:/Users/Administrator/Desktop/stockBoardRef_incremental"
```

**对比逻辑**：
- 只比较每只股票所属的**板块代码集合**（`quote_code`）
- 忽略 `rise_cnt`、`fall_cnt` 等每日波动的统计值
- 只有板块集合真正变化时才入库

**效果**：通常 5000+ 只股票中只有 ~10~30 只的板块关系会变化，增量入库只需几秒。

---

## 8. 踩坑记录与常见问题

### 坑 1：JDK 版本不对导致构建失败

```
Failed to execute goal maven-compiler-plugin:3.11.0:compile: 无效的标记: --release
```

**原因**：项目 POM 中配置了 `<maven.compiler.release>21</maven.compiler.release>`，但默认 `JAVA_HOME` 指向了 JDK 8。

**解决**：构建前切换到 JDK 21：
```bash
set JAVA_HOME=D:\Development\Environment\Jdk\jdk-21.0.11
set PATH=%JAVA_HOME%\bin;%PATH%
```

### 坑 2：Mapper Bean 注入失败

```
Parameter 0 of constructor in StockAnomalyInitService required a bean of type 'StockAnomalyMapper' that could not be found.
```

**原因**：双数据源架构下 `@Mapper` 注解不生效。

**解决**：
1. 删掉 Mapper 接口上的 `@Mapper` 注解
2. 在 `DataSourceConfig.java` 中显式注册 `MapperFactoryBean`

### 坑 3：ClickHouse DateTime 解析失败

```
Cannot parse string '2026-08-17 13:24:23.2546284' as DateTime: syntax error at position 19
```

**原因**：`LocalDateTime.now()` 包含纳秒（如 `.2546284`），ClickHouse 的 `DateTime` 类型不支持这种格式。

**解决**：截断到秒精度：
```java
LocalDateTime now = LocalDateTime.now().truncatedTo(ChronoUnit.SECONDS);
```

### 坑 4：mitmproxy 证书安装后仍无法抓 HTTPS

**原因**：Android 7+ 不再信任用户安装的证书，需要将证书安装到系统信任区。

**解决**：
1. 模拟器需要 Root 权限
2. 使用 `mount --bind` 将证书挂载到 `/system/etc/security/cacerts`
3. 参考 `fix_proxy_cert.bat` 脚本

### 坑 5：同花顺 Cookie 过期

**现象**：爬虫返回 401 或重定向到登录页。

**原因**：Cookie 有效期通常 7-30 天。

**解决**：重新运行 `TonghuashunLogin.java` 导出 Cookie。

### 坑 6：CloakBrowser 启动失败

```
cloakbrowser not installed
```

**解决**：
```bash
pip install cloakbrowser
python -m cloakbrowser install
```

### 坑 7：爬虫脚本最后的 NameError

```
NameError: name 'results' is not defined.
```

**原因**：`crawl_all()` 函数末尾有个多余的 `return results`，但 `results` 变量从未定义。

**影响**：**无影响**。数据已经全部保存完毕，这只是最后一句无关紧要的代码。可以忽略或删掉。

### 坑 8：端口被占用导致新实例启动失败

```
Web server failed to start. Port 8081 was already in use.
```

**原因**：旧的 Java 进程还没死掉。

**解决**：
```bash
# 杀掉所有 Java 进程
taskkill //F //IM java.exe

# 或者先查端口占用
netstat -ano | findstr 8081
taskkill //F //PID <进程ID>
```

### 坑 9：增量目录残留旧文件

如果增量目录 `stockBoardRef_incremental` 没有先清空，旧文件会混入导致重复入库。

**解决**：每次增量前先删除重建：
```bash
rm -rf stockBoardRef_incremental
```

---

## 9. 附录：一键启动脚本

### `start_app.bat`（启动 Java 服务）

```bat
@echo off
chcp 65001 >nul

:: 切换到 JDK 21
set JAVA_HOME=D:\Development\Environment\Jdk\jdk-21.0.11
set PATH=%JAVA_HOME%\bin;%PATH%

cd /d D:\Development\IDEAWorkSpace\Github\new\crawler-backend

echo [1/2] 正在构建项目...
call mvn -q -DskipTests clean package
if %ERRORLEVEL% NEQ 0 (
    echo [ERROR] 构建失败！
    pause
    exit /b 1
)

echo [2/2] 正在启动应用...
start java -jar crawler-admin\target\crawler-admin-0.1.0.jar

echo.
echo 应用已启动，端口 8081
echo 等待启动完成后，访问: http://localhost:8081/api/init/stock-anomaly
pause
```

### `daily_crawl.bat`（每日爬取 + 入库）

```bat
@echo off
chcp 65001 >nul
cd /d C:\Users\Administrator\Desktop

echo ========================================
echo  每日数据爬取 - %date% %time%
echo ========================================

echo.
echo [1/4] 爬取异动数据（约 2 小时）...
python ths_anomaly_crawler.py --all --workers 2 --delay 1.0

echo.
echo [2/4] 备份昨天的板块数据...
if exist stockBoardRef_yesterday_backup rmdir /s /q stockBoardRef_yesterday_backup
rename stockBoardRef stockBoardRef_yesterday_backup

echo.
echo [3/4] 爬取板块关联（约 1.5 小时）...
python stock_board_ref.py --all --workers 2 --delay 1.0

echo.
echo [4/4] 对比增量并入库...
python -c "
import os, json, shutil
today_dir = 'stockBoardRef'
backup_dir = 'stockBoardRef_yesterday_backup'
incr_dir = 'stockBoardRef_incremental'
os.makedirs(incr_dir, exist_ok=True)
today_files = set(f for f in os.listdir(today_dir) if f.endswith('.json'))
backup_files = set(f for f in os.listdir(backup_dir) if f.endswith('.json'))
def get_codes(fpath):
    with open(fpath, 'r', encoding='utf-8') as f:
        data = json.load(f)
    return set(b.get('quote_code', '') for b in data.get('boards', []))
for f in today_files - backup_files:
    shutil.copy2(os.path.join(today_dir, f), os.path.join(incr_dir, f))
for f in today_files & backup_files:
    if get_codes(os.path.join(today_dir, f)) != get_codes(os.path.join(backup_dir, f)):
        shutil.copy2(os.path.join(today_dir, f), os.path.join(incr_dir, f))
print(f'增量文件: {len(os.listdir(incr_dir))}')
"

echo.
echo 正在调用入库接口...

echo --- 异动数据 ---
curl -s -X POST http://localhost:8081/api/init/stock-anomaly

echo.
echo --- 板块关联（增量）---
curl -s -X POST "http://localhost:8081/api/init/stock-board-rel?dir=C:/Users/Administrator/Desktop/stockBoardRef_incremental"

echo.
echo ========================================
echo  全部完成！
echo ========================================
pause
```

---

## 总结

| 步骤 | 命令 | 耗时 |
|---|---|---|
| 1. 爬取异动数据 | `python ths_anomaly_crawler.py --all --workers 2 --delay 1.0` | ~2h |
| 2. 异动入库 | `curl -X POST localhost:8081/api/init/stock-anomaly` | 几分钟 |
| 3. 备份 + 爬取板块 | `rename` + `python stock_board_ref.py --all` | ~1.5h |
| 4. 对比增量 + 入库 | `python 对比脚本` + `curl` | 几秒 |

**核心设计**：
- 异动数据：按 `anomaly_id` 数据库级别去重，只入库新记录
- 板块关联：按文件级别对比，只入库有变化的股票
- ClickHouse `ReplacingMergeTree` 作为最终兜底，即使重复插入也会自动合并
