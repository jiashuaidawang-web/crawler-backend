# P0 同花顺验证 — 详细实现方案

## 目标

验证 **Playwright + cloak（反检测）** 这套能不能稳稳抓下同花顺的板块数据。
这是 V2 的前置门槛——走不通则后续同花顺相关全部砍掉。

**两个子任务**：
- **P0-1** `THS_PLATE`：同花顺板块基础维表（地域 33 + 行业 76 + 概念 375）
- **P0-2** `THS_PLATE_STOCK`：同花顺板块-个股关系（AJAX + cookie）

---

## 一、现有基础设施（复用）

| 组件 | 路径 | 用途 |
|------|------|------|
| `TonghuashunBrowserStrategy` | crawler-strategy/tonghuashun/ | 浏览器抓取主策略，已实现 `extract` 结构化抽取 |
| `BrowserPool` | crawler-strategy/tonghuashun/ | 浏览器对象池，支持 SELF / CLOAK 模式 |
| `BrowserContextFactory` | crawler-strategy/tonghuashun/ | 上下文工厂：stealth JS、Cookie 注入 |
| `TonghuashunLogin` | crawler-strategy/tonghuashun/ | 登录 + Cookie 导出（半自动过滑块） |
| `StrategyFactoryConfig` | crawler-worker/config/ | 已装配 `TonghuashunBrowserStrategy` |
| `ClaimLoop` | crawler-worker/scheduler/ | 认领 → fetch → DedupWriter 落库 → complete |
| `DedupWriter` | crawler-persistence/service/ | 按 taskType 路由写入各表 |

**结论**：基础设施齐全，P0 只新增"业务逻辑"（seeder + 解析 + 落表），不用动框架。

---

## 二、P0-1：同花顺板块基础维表（THS_PLATE）

### 2.1 目标数据

| 板块类型 | URL | 数量 |
|---------|-----|------|
| 地域 | `http://q.10jqka.com.cn/dy/` | ~33 |
| 行业 | `http://q.10jqka.com.cn/thshy/` | ~76 |
| 概念 | `http://q.10jqka.com.cn/gn/` | ~375 |

**关键操作**：概念页面必须先点"显示全部"按钮（`/html/body/div[2]/div[2]/a`），否则只显示 20 个。

### 2.2 表设计

```sql
-- 同花顺板块基础维表（V2 新增）
CREATE TABLE IF NOT EXISTS ths_plate (
    id              UInt64,
    plate_type      UInt8 NOT NULL COMMENT '板块类型：4地域 5行业 6概念（与东财 1/2/3 区分）',
    plate_code      String NOT NULL COMMENT '同花顺板块代码（如 307408）',
    plate_name      String NOT NULL COMMENT '板块名称',
    plate_index     String COMMENT '板块指数代码',
    lead_stock_code String COMMENT '领涨股代码',
    lead_stock_name String COMMENT '领涨股名称',
    cur_price       Decimal64(4) COMMENT '当前价格',
    increase        Decimal64(4) COMMENT '涨跌幅%',
    turnover        Decimal64(2) COMMENT '成交额(元)',
    volume          UInt64 COMMENT '成交量(手)',
    up_count        UInt16 COMMENT '上涨家数',
    down_count      UInt16 COMMENT '下跌家数',
    trade_date      Date NOT NULL COMMENT '数据日期',
    data_source     UInt8 DEFAULT 0 COMMENT '0=同花顺',
    create_date     DateTime DEFAULT now(),
    update_date     DateTime DEFAULT now()
) ENGINE = MergeTree()
PARTITION BY toYYYYMM(trade_date)
ORDER BY (plate_type, plate_code, trade_date);
```

> **说明**：`plate_type` 用 4/5/6（而非 1/2/3）是为了跟东财的 `board_basic` 表（1=地域 2=行业 3=概念）区分来源。同花顺的板块体系与东财不完全一致。

### 2.3 抓取流程（TonghuashunBrowserStrategy 扩展）

```
对每个 (plateType, url) in [(4,dy),(5,thshy),(6,gn)]:
  1. 浏览器打开 url
  2. waitSelector = ".cate_items"（板块分类容器）
  3. 如果是概念(plateType=6)：点击 "显示全部" 按钮
     page.click("a.more, a:has-text('显示全部'), /html/body/div[2]/div[2]/a")
  4. 遍历 .cate_items 内的 <a> 标签：
     - 取 href（板块详情页 URL）
     - 取 text（板块名称）
  5. 对每个板块详情页 href：
     - 打开 href
     - waitSelector = "h3"（板块标题）
     - 取 plate_code: h3 span 里的数字码
     - 取 cur_price / increase / turnover 等行情数据
  6. 组装 rows 落 ths_plate
```

### 2.4 种子设计（SeedGenerator 新增）

```java
/**
 * 同花顺板块基础维表（THS_PLATE）：每日 3 个任务（地域/行业/概念）。
 * <p>浏览器策略，单任务串行跑完所有板块（375 个概念单线程约 15-20 分钟）。</p>
 */
public int seedThsPlate(int source, String date) {
    int inserted = 0;
    // plateType 4=地域 5=行业 6=概念
    int[] plateTypes = {4, 5, 6};
    for (int plateType : plateTypes) {
        String params = String.format("{\"plateType\":%d,\"tradeDate\":\"%s\"}", plateType, date);
        CrawlTask task = buildTask("THS_PLATE", source, date, null, null, params);
        task.setUniqueKey("THS_PLATE|" + source + "|" + date + "|" + plateType);
        // 浏览器任务优先级调低（耗时长）
        task.setPriority(3);
        inserted += mapper.insertIfAbsent(task);
    }
    return inserted;
}
```

### 2.5 写入路由（DedupWriter 新增）

```java
case "THS_PLATE" -> writeThsPlate(rows, source, srcDetail);

private void writeThsPlate(List<Map<String, Object>> rows, SourceType source, String srcDetail) {
    List<ThsPlate> batch = new ArrayList<>(rows.size());
    for (Map<String, Object> r : rows) {
        ThsPlate e = new ThsPlate();
        e.setPlateType(intVal(r.get("plate_type")));
        e.setPlateCode(str(r.get("plate_code")));
        e.setPlateName(str(r.get("plate_name")));
        e.setPlateIndex(str(r.get("plate_index")));
        e.setLeadStockCode(str(r.get("lead_stock_code")));
        e.setLeadStockName(str(r.get("lead_stock_name")));
        e.setCurPrice(bigDec(r.get("cur_price")));
        e.setIncrease(bigDec(r.get("increase")));
        e.setTurnover(bigDec(r.get("turnover")));
        e.setVolume(bigDec(r.get("volume")).longValue());
        e.setUpCount(intVal(r.get("up_count")));
        e.setDownCount(intVal(r.get("down_count")));
        e.setTradeDate(toLocalDate(r.get("trade_date")));
        e.setDataSource(source.getCode());
        e.setSrcDetail(srcDetail);
        batch.add(e);
    }
    insertInChunks(thsPlateMapper::batchInsert, batch, "ths_plate", source);
}
```

### 2.6 TaskTypeCatalog 新增

```java
new TaskSpec("THS_PLATE", 0, true, false, null, "同花顺板块基础维表（地域/行业/概念，浏览器策略）"),
```

> sourceCode=0（同花顺），marketWide=true（市场级，每日 3 条）

### 2.7 手动触发接口（JobController 新增）

```java
@PostMapping("/seed-ths-plate")
public Map<String, Object> seedThsPlate(
        @RequestParam(required = false) @DateTimeFormat(pattern = "yyyy-MM-dd") LocalDate date,
        @RequestParam(required = false, defaultValue = "0") int source) {
    String d = (date == null ? LocalDate.now() : date).format(DateTimeFormatter.ofPattern("yyyy-MM-dd"));
    int inserted = seedGenerator.seedThsPlate(source, d);
    Map<String, Object> r = new HashMap<>();
    r.put("taskType", "THS_PLATE");
    r.put("date", d);
    r.put("inserted", inserted);
    return r;
}
```

---

## 三、P0-2：同花顺板块-个股关系（THS_PLATE_STOCK）

### 3.1 目标数据

每个板块包含哪些个股，按页翻页抓完。

**URL 模板**：
```
http://q.10jqka.com.cn/{gn|hy}/detail/field/{tar}/order/desc/page/{page}/ajax/1/code/{plateCode}
```
- `tar=199112`：概念板块
- `tar=264648`：行业板块
- 返回 HTML 表格（`<tbody>`），需 Jsoup 解析

**关键操作**：
1. 先浏览器访问板块主页 `http://q.10jqka.com.cn/gn/detail/code/{plateCode}/` **取 cookie**
2. 再带 cookie 请求 AJAX URL（HTML 表格）
3. 解析 `.page_info` 取总页数，循环翻页

### 3.2 表设计

```sql
-- 同花顺板块-个股关系表（V2 新增）
CREATE TABLE IF NOT EXISTS ths_stock_board_rel (
    id              UInt64,
    plate_type      UInt8 NOT NULL COMMENT '4地域 5行业 6概念',
    plate_code      String NOT NULL COMMENT '同花顺板块代码',
    plate_name      String COMMENT '板块名称',
    symbol          String NOT NULL COMMENT '股票代码（如 600000）',
    stock_name      String COMMENT '股票名称',
    trade_date      Date NOT NULL COMMENT '数据日期',
    data_source     UInt8 DEFAULT 0 COMMENT '0=同花顺',
    create_date     DateTime DEFAULT now(),
    update_date     DateTime DEFAULT now()
) ENGINE = MergeTree()
PARTITION BY toYYYYMM(trade_date)
ORDER BY (plate_code, symbol, trade_date);
```

### 3.3 抓取流程

```
前置：从 ths_plate 表读当日所有 plate_code + plate_type

对每个 plate:
  1. 浏览器访问主页取 cookie：
     url = "http://q.10jqka.com.cn/gn/detail/code/{plateCode}/"
     page.navigate(url)
     cookies = context.cookies()  // Playwright API
  2. 取第一页（探测总页数）：
     tar = (plateType==5) ? 264648 : 199112
     url = "http://q.10jqka.com.cn/gn/detail/field/{tar}/order/desc/page/1/ajax/1/code/{plateCode}"
     html = httpClient.get(url, cookies)  // 复用 OkHttp，带 cookie
     doc = Jsoup.parse(html)
     totalPages = parse(".page_info")  // "1/12" → 12
  3. 循环 page=1..totalPages：
     - 请求 AJAX URL
     - Jsoup 解析 tbody → 每行取 symbol + stock_name
     - 组装 rows
  4. 落 ths_stock_board_rel
```

### 3.4 种子设计

```java
/**
 * 同花顺板块-个股关系（THS_PLATE_STOCK）：从 ths_plate 读板块，每个板块一个任务。
 * <p>依赖 THS_PLATE 先跑完；单任务内翻页抓完该板块所有个股。</p>
 */
public int seedThsPlateStock(int source, String date) {
    // 读当日 ths_plate 的板块列表
    List<ThsPlate> plates = thsPlateMapper.selectByTradeDate(LocalDate.parse(date));
    int inserted = 0;
    for (ThsPlate plate : plates) {
        String params = String.format(
            "{\"plateType\":%d,\"plateCode\":\"%s\",\"plateName\":\"%s\",\"tradeDate\":\"%s\"}",
            plate.getPlateType(), plate.getPlateCode(), plate.getPlateName(), date);
        CrawlTask task = buildTask("THS_PLATE_STOCK", source, date, null, null, params);
        task.setUniqueKey("THS_PLATE_STOCK|" + source + "|" + date + "|" + plate.getPlateCode());
        task.setPriority(3);
        inserted += mapper.insertIfAbsent(task);
    }
    return inserted;
}
```

### 3.5 写入路由（DedupWriter 新增）

```java
case "THS_PLATE_STOCK" -> writeThsStockBoardRel(rows, source, srcDetail);

private void writeThsStockBoardRel(List<Map<String, Object>> rows, SourceType source, String srcDetail) {
    List<ThsStockBoardRel> batch = new ArrayList<>(rows.size());
    for (Map<String, Object> r : rows) {
        ThsStockBoardRel e = new ThsStockBoardRel();
        e.setPlateType(intVal(r.get("plate_type")));
        e.setPlateCode(str(r.get("plate_code")));
        e.setPlateName(str(r.get("plate_name")));
        e.setSymbol(str(r.get("symbol")));
        e.setStockName(str(r.get("stock_name")));
        e.setTradeDate(toLocalDate(r.get("trade_date")));
        e.setDataSource(source.getCode());
        e.setSrcDetail(srcDetail);
        batch.add(e);
    }
    insertInChunks(thsStockBoardRelMapper::batchInsert, batch, "ths_stock_board_rel", source);
}
```

---

## 四、新增文件清单

### 4.1 数据库 / 实体 / Mapper（crawler-persistence）

| 文件 | 用途 |
|------|------|
| `V2_001__create_ths_plate.sql` | ths_plate 表 DDL |
| `V2_002__create_ths_stock_board_rel.sql` | ths_stock_board_rel 表 DDL |
| `entity/ThsPlate.java` | ths_plate 实体 |
| `entity/ThsStockBoardRel.java` | ths_stock_board_rel 实体 |
| `mapper/ThsPlateMapper.java` | ths_plate Mapper |
| `mapper/ThsStockBoardRelMapper.java` | ths_stock_board_rel Mapper |

### 4.2 策略层（crawler-strategy）

| 文件 | 用途 |
|------|------|
| `tonghuashun/ThsPlateCrawler.java` | P0-1 抓取逻辑（板块列表 + 详情） |
| `tonghuashun/ThsPlateStockCrawler.java` | P0-2 抓取逻辑（AJAX + Jsoup 翻页） |

### 4.3 种子 / 调度 / 写入（crawler-admin + crawler-persistence）

| 文件 | 修改 |
|------|------|
| `TaskTypeCatalog.java` | 新增 THS_PLATE / THS_PLATE_STOCK |
| `SeedGenerator.java` | 新增 seedThsPlate / seedThsPlateStock |
| `JobController.java` | 新增 /seed-ths-plate / /seed-ths-plate-stock |
| `DedupWriter.java` | 新增 writeThsPlate / writeThsStockBoardRel |

---

## 五、关键实现细节

### 5.1 概念页面"显示全部"按钮

```java
// TonghuashunBrowserStrategy 内（或 ThsPlateCrawler）
if (plateType == 6) {
    try {
        // 多种选择器兜底（同花顺可能改 DOM）
        Locator moreBtn = page.locator("a.more");
        if (moreBtn.count() == 0) {
            moreBtn = page.locator("a:has-text('显示全部')");
        }
        if (moreBtn.count() == 0) {
            moreBtn = page.locator("xpath=/html/body/div[2]/div[2]/a");
        }
        if (moreBtn.count() > 0) {
            moreBtn.first().click();
            page.waitForTimeout(2000); // 等展开动画
        }
    } catch (Exception e) {
        log.warn("点击'显示全部'失败，继续尝试: {}", e.getMessage());
    }
}
```

### 5.2 板块代码提取（详情页）

```java
// 概念板块：html/body/div[2]/div[3]/div[2]/div/div[2]/div[1]/div[1]/h3/span
// 地域/行业：/html/body/div[2]/div[2]/div[2]/div/div/div[1]/div[1]/h3/span
String xpath = (plateType == 6)
    ? "html/body/div[2]/div[3]/div[2]/div/div[2]/div[1]/div[1]/h3/span"
    : "/html/body/div[2]/div[2]/div[2]/div/div/div[1]/div[1]/h3/span";
String plateIndex = page.locator("xpath=" + xpath).first().innerText();
```

### 5.3 AJAX + Cookie 翻页（P0-2）

```java
// 1. 浏览器取 cookie
page.navigate("http://q.10jqka.com.cn/gn/detail/code/" + plateCode + "/");
page.waitForLoadState(LoadState.NETWORKIDLE);
List<Cookie> cookies = page.context().cookies();

// 2. 拼 cookie 字符串
StringBuilder sb = new StringBuilder();
for (Cookie c : cookies) {
    sb.append(c.name).append("=").append(c.value).append("; ");
}
String cookieStr = sb.toString();

// 3. OkHttp 请求 AJAX
String url = String.format(
    "http://q.10jqka.com.cn/gn/detail/field/%d/order/desc/page/%d/ajax/1/code/%s",
    tar, pageNum, plateCode);
Request req = new Request.Builder().url(url)
    .header("Cookie", cookieStr)
    .header("User-Agent", "Mozilla/5.0 ...")
    .header("Referer", "http://q.10jqka.com.cn/gn/detail/code/" + plateCode + "/")
    .build();
String html = okHttpClient.newCall(req).execute().body().string();

// 4. Jsoup 解析 tbody
Document doc = Jsoup.parse(html);
Elements rows = doc.select("tbody tr");
for (Element row : rows) {
    String symbol = row.select("td:eq(1) a").text();   // 股票代码
    String name   = row.select("td:eq(2) a").text();   // 股票名称
    // ...
}
```

### 5.4 反爬 / 限流

- **浏览器任务优先级调低**（priority=3），避免阻塞高频东财任务
- **单线程串行**：同花顺浏览器任务不并发（BrowserPool 单实例）
- **请求间隔**：板块详情页之间 `Thread.sleep(1000~2000)`
- **Cookie 复用**：一次登录，cookie 存 `cookies/q.10jqka.com.cn.json`，后续任务直接加载

---

## 六、验证标准（P0 完成定义）

| 检查项 | 标准 |
|--------|------|
| 地域板块 | 抓到 ≥ 30 个（目标 33） |
| 行业板块 | 抓到 ≥ 70 个（目标 76） |
| 概念板块 | 抓到 ≥ 350 个（目标 375，"显示全部"必须生效） |
| 板块-个股 | 每个板块翻页到底，合计 ≥ 10 万行/天 |
| 稳定性 | 连续跑 3 天，不触发反爬 / 不丢 cookie |
| 耗时 | THS_PLATE 单任务 ≤ 30 分钟；THS_PLATE_STOCK 全部 ≤ 2 小时 |

**验证命令**：
```bash
# 1. 先登录拿 cookie（一次性，半自动过滑块）
java -cp ... TonghuashunLogin <username> <password>

# 2. 下发 THS_PLATE 任务
curl -X POST "http://localhost:8081/api/job/seed-ths-plate" -d "date=2026-08-09&source=0"

# 3. THS_PLATE 跑完后，下发 THS_PLATE_STOCK
curl -X POST "http://localhost:8081/api/job/seed-ths-plate-stock" -d "date=2026-08-09&source=0"

# 4. 查 CK 数据
curl -X POST "http://localhost:8081/api/monitor/health"  # 看任务状态
```

---

## 七、风险与降级

| 风险 | 降级方案 |
|------|----------|
| cloak 连不上 / Playwright 崩溃 | 回退 SELF 模式（自管 Chromium + stealth JS） |
| cookie 过期频繁 | 接入 `TonghuashunLogin` 半自动流程，人工定期刷新 |
| 概念"显示全部"按钮 DOM 改 | 多选择器兜底 + xpath 硬编码备选 |
| AJAX 被反爬（403/验证码） | 降级到东财 `push2.../clist/get?fs=b:BKxxx` 接口 |
| 单任务超时（375 概念 × N 页） | 按 plateType 拆 3 个任务，或按首字母分片 |

**最终降级**：如果同花顺完全不通，P0-2 改用东财接口：
```
http://push2.eastmoney.com/weblogin/api/qt/clist/get?fs=b:bk{boardCode}+f:!50&fields=f12,f14
```
这与现有 `STOCK_BY_BOARD` 完全一致，只是 boardCode 从 ths_plate 取。

---

## 八、工时估算

| 子任务 | 工时 |
|--------|------|
| 表 DDL + 实体 + Mapper | 0.5d |
| ThsPlateCrawler（含"显示全部"） | 1.5d |
| ThsPlateStockCrawler（AJAX + Jsoup） | 1.5d |
| SeedGenerator + JobController | 0.5d |
| DedupWriter 路由 | 0.5d |
| 端到端调试验证 | 1d |
| **合计** | **~5.5 人日** |

---

## 九、附录：ThsPlateCrawler 完整代码

### 9.1 设计说明

`ThsPlateCrawler` 是 P0-1 的核心抓取类，**不实现 `SourceStrategy` 接口**，而是作为业务爬虫被 `TonghuashunBrowserStrategy` 调用（按 `taskType == "THS_PLATE"` 路由）。

**职责**：
1. 接收 `plateType`（4=地域 / 5=行业 / 6=概念）和 `tradeDate`
2. 打开同花顺板块列表页 → 概念页点"显示全部" → 遍历 `.cate_items > a"` 取板块名 + 详情页 URL
3. 逐个打开板块详情页 → 取板块代码（指数代码）+ 行情数据（涨幅/成交额/领涨股等）
4. 返回 `List<Map<String, Object>>`，由 `TonghuashunBrowserStrategy` 包装成 `CrawlResult`

**关键选择器**（对标项目 `ThsPlateSpider` 实测）：

| 数据 | 选择器 | 备注 |
|------|--------|------|
| 板块列表容器 | `.cate_items` | 每个分类一个 cate_items |
| 板块链接 | `.cate_items > a` | href=详情页 URL, text=板块名 |
| 概念"显示全部" | `a.more` / `a:has-text('显示全部')` | 概念页默认只显示 20 个 |
| 板块代码（概念） | `html/body/div[2]/div[3]/div[2]/div/div[2]/div[1]/div[1]/h3/span` | 概念详情页 |
| 板块代码（地域/行业） | `/html/body/div[2]/div[2]/div[2]/div/div/div[1]/div[1]/h3/span` | 地域/行业详情页 |
| 当前价格 | `html/body/div[2]/div[3]/div[2]/div/div[2]/div[1]/div[1]/span` | 概念页 |
| 涨跌幅 | `html/body/div[2]/div[3]/div[2]/div/div[2]/div[1]/div[2]/dl[6]/dd` | 概念页 |
| 成交额 | `html/body/div[2]/div[3]/div[2]/div/div[2]/div[1]/div[2]/dl[10]/dd` | 单位亿,需 ×100000000 |
| 成交量 | `html/body/div[2]/div[3]/div[2]/div/div[2]/div[1]/div[2]/dl[5]/dd` | 单位万手,需 ×10000 |
| 上涨家数 | `html/body/div[2]/div[3]/div[2]/div/div[2]/div[1]/div[2]/dl[8]/dd/span[1]` | 概念页 |
| 下跌家数 | `html/body/div[2]/div[3]/div[2]/div/div[2]/div[1]/div[2]/dl[8]/dd/span[2]` | 概念页 |

> ⚠️ **注意**：同花顺 DOM 可能随时改版，所有 xpath 都需多选择器兜底 + 端到端实测验证。

### 9.2 ThsPlateCrawler.java

```java
package com.dunwugudao.crawler.strategy.tonghuashun;

import com.dunwugudao.crawler.core.config.AntiCrawlConfig;
import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.ElementHandle;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.options.LoadState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 同花顺板块基础维表爬虫（P0-1 THS_PLATE）。
 *
 * <p>抓取同花顺地域/行业/概念板块，流程：
 * <ol>
 *   <li>打开板块列表页（dy/thshy/gn）</li>
 *   <li>概念页点击"显示全部"按钮（默认只显示 20 个，总共 375 个）</li>
 *   <li>遍历 {@code .cate_items > a} 取板块名 + 详情页 URL</li>
 *   <li>逐个打开详情页，取板块代码（指数代码）+ 行情数据</li>
 * </ol>
 *
 * <p>不实现 {@link com.dunwugudao.crawler.core.strategy.SourceStrategy}，
 * 由 {@link TonghuashunBrowserStrategy} 按 taskType 路由调用。</p>
 *
 * <p>⚠️ 所有 xpath 基于对标项目（market）实测，同花顺 DOM 可能改版，需多选择器兜底。</p>
 */
@Component
public class ThsPlateCrawler {

    private static final Logger log = LoggerFactory.getLogger(ThsPlateCrawler.class);

    private final BrowserPool browserPool;
    private final BrowserContextFactory contextFactory;

    /** 板块类型 → 列表页 URL */
    private static final Map<Integer, String> PLATE_TYPE_URL = Map.of(
            4, "http://q.10jqka.com.cn/dy/",      // 地域
            5, "http://q.10jqka.com.cn/thshy/",   // 行业
            6, "http://q.10jqka.com.cn/gn/"       // 概念
    );

    private static final String HOST = "q.10jqka.com.cn";

    public ThsPlateCrawler(BrowserPool browserPool) {
        this.browserPool = browserPool;
        this.contextFactory = new BrowserContextFactory();
    }

    /**
     * 抓取指定类型的同花顺板块。
     *
     * @param plateType 4=地域 5=行业 6=概念
     * @param tradeDate 数据日期（yyyy-MM-dd）
     * @param cfg       反爬配置
     * @return 行数据列表（key=ths_plate 表列名，必含 trade_date）
     */
    public List<Map<String, Object>> crawl(int plateType, String tradeDate, AntiCrawlConfig cfg) {
        String listUrl = PLATE_TYPE_URL.get(plateType);
        if (listUrl == null) {
            log.warn("[ThsPlateCrawler] 未知 plateType={}, 跳过", plateType);
            return new ArrayList<>();
        }

        Browser browser = browserPool.acquire(cfg);
        BrowserContext context = null;
        List<Map<String, Object>> allRows = new ArrayList<>();
        Page listPage = null;

        try {
            context = contextFactory.newContext(browser, cfg, HOST);

            // ===== 第一步：打开板块列表页，取板块名 + 详情页 URL =====
            listPage = context.newPage();
            log.info("[ThsPlateCrawler] 打开列表页: plateType={}, url={}", plateType, listUrl);
            listPage.navigate(listUrl);
            listPage.waitForLoadState(LoadState.NETWORKIDLE);
            listPage.waitForSelector(".cate_items", new Page.WaitForSelectorOptions().setTimeout(30_000));
            Thread.sleep(1500); // 等 JS 渲染

            // 概念页：点击"显示全部"按钮（默认只显示 20 个概念）
            if (plateType == 6) {
                clickShowAll(listPage);
            }

            // 遍历 .cate_items 下的 <a> 标签
            List<ElementHandle> cateItems = listPage.querySelectorAll(".cate_items");
            log.info("[ThsPlateCrawler] plateType={}, cate_items 数量={}", plateType, cateItems.size());

            List<PlateRef> plateRefs = new ArrayList<>();
            for (ElementHandle cateItem : cateItems) {
                List<ElementHandle> links = cateItem.querySelectorAll("a");
                for (ElementHandle a : links) {
                    String href = a.getAttribute("href");
                    String text = a.innerText();
                    if (href != null && !href.isBlank() && text != null && !text.isBlank()) {
                        plateRefs.add(new PlateRef(text.trim(), href.trim()));
                    }
                }
            }
            log.info("[ThsPlateCrawler] plateType={}, 取到 {} 个板块链接", plateType, plateRefs.size());

            // 关闭列表页
            listPage.close();
            listPage = null;

            // ===== 第二步：逐个打开详情页，取板块代码 + 行情数据 =====
            int successCount = 0;
            int failCount = 0;
            for (int i = 0; i < plateRefs.size(); i++) {
                PlateRef ref = plateRefs.get(i);
                try {
                    Map<String, Object> row = crawlPlateDetail(context, plateType, ref, tradeDate, i, plateRefs.size());
                    if (row != null) {
                        allRows.add(row);
                        successCount++;
                    }
                    // 礼貌延迟，避免反爬
                    Thread.sleep(800 + (long) (Math.random() * 700));
                } catch (Exception e) {
                    failCount++;
                    log.warn("[ThsPlateCrawler] 板块详情抓取失败({}/{}): {}, url={}",
                            i + 1, plateRefs.size(), ref.name, ref.url);
                }
            }
            log.info("[ThsPlateCrawler] plateType={}, 完成: 成功={}, 失败={}, 总计={}",
                    plateType, successCount, failCount, plateRefs.size());

        } catch (Exception e) {
            log.error("[ThsPlateCrawler] plateType={} 抓取异常: {}", plateType, e.getMessage(), e);
        } finally {
            if (listPage != null && !listPage.isClosed()) {
                try { listPage.close(); } catch (Exception ignored) {}
            }
            if (context != null) {
                try { context.close(); } catch (Exception ignored) {}
            }
        }
        return allRows;
    }

    /**
     * 点击概念页的"显示全部"按钮。
     * <p>同花顺概念页默认只显示前 20 个，必须点这个按钮才能展示全部 375 个。</p>
     */
    private void clickShowAll(Page page) {
        // 多种选择器兜底（同花顺可能改 DOM）
        String[] selectors = {
                "a.more",
                "a:has-text('显示全部')",
                "a:has-text('更多')",
                ".cate_items a.more",
                "xpath=/html/body/div[2]/div[2]/a"
        };
        for (String sel : selectors) {
            try {
                Locator btn = page.locator(sel).first();
                if (btn.count() > 0 && btn.isVisible()) {
                    log.info("[ThsPlateCrawler] 点击'显示全部'按钮, selector={}", sel);
                    btn.click();
                    page.waitForTimeout(2000); // 等展开动画 + 懒加载
                    return;
                }
            } catch (Exception ignored) {
            }
        }
        log.warn("[ThsPlateCrawler] 未找到'显示全部'按钮, 继续尝试（可能已默认全量展示）");
    }

    /**
     * 抓取单个板块详情页。
     *
     * @return 行数据，失败返回 null
     */
    private Map<String, Object> crawlPlateDetail(BrowserContext context, int plateType,
                                                  PlateRef ref, String tradeDate,
                                                  int index, int total) {
        Page page = context.newPage();
        try {
            page.navigate(ref.url);
            page.waitForLoadState(LoadState.NETWORKIDLE);
            // 等板块标题渲染
            page.waitForSelector("h3", new Page.WaitForSelectorOptions().setTimeout(15_000));
            Thread.sleep(800);

            Map<String, Object> row = new HashMap<>();
            row.put("plate_type", plateType);
            row.put("plate_name", ref.name);
            row.put("trade_date", tradeDate);

            // 板块代码（指数代码）— 概念和地域/行业在不同 xpath
            String codeXpath = (plateType == 6)
                    ? "html/body/div[2]/div[3]/div[2]/div/div[2]/div[1]/div[1]/h3/span"
                    : "/html/body/div[2]/div[2]/div[2]/div/div/div[1]/div[1]/h3/span";
            String plateIndex = safeInnerText(page, codeXpath);
            row.put("plate_index", plateIndex);

            // 当前价格
            String priceXpath = (plateType == 6)
                    ? "html/body/div[2]/div[3]/div[2]/div/div[2]/div[1]/div[1]/span"
                    : "/html/body/div[2]/div[2]/div[2]/div/div/div[1]/div[1]/span";
            String priceText = safeInnerText(page, priceXpath);
            row.put("cur_price", parseDecimal(priceText));

            // 涨跌幅（%）
            String increaseXpath = (plateType == 6)
                    ? "html/body/div[2]/div[3]/div[2]/div/div[2]/div[1]/div[2]/dl[6]/dd"
                    : "/html/body/div[2]/div[2]/div[2]/div/div/div[1]/div[2]/dl[6]/dd";
            String increaseText = safeInnerText(page, increaseXpath);
            row.put("increase", parsePercent(increaseText));

            // 成交额（亿 → 元）
            String turnoverXpath = (plateType == 6)
                    ? "html/body/div[2]/div[3]/div[2]/div/div[2]/div[1]/div[2]/dl[10]/dd"
                    : "/html/body/div[2]/div[2]/div[2]/div/div/div[1]/div[2]/dl[10]/dd";
            String turnoverText = safeInnerText(page, turnoverXpath);
            row.put("turnover", parseYi(turnoverText)); // 亿 → 元

            // 成交量（万手 → 手）
            String volumeXpath = (plateType == 6)
                    ? "html/body/div[2]/div[3]/div[2]/div/div[2]/div[1]/div[2]/dl[5]/dd"
                    : "/html/body/div[2]/div[2]/div[2]/div/div/div[1]/div[2]/dl[5]/dd";
            String volumeText = safeInnerText(page, volumeXpath);
            row.put("volume", parseWanShou(volumeText)); // 万手 → 手

            // 上涨家数
            String upXpath = (plateType == 6)
                    ? "html/body/div[2]/div[3]/div[2]/div/div[2]/div[1]/div[2]/dl[8]/dd/span[1]"
                    : "/html/body/div[2]/div[2]/div[2]/div/div/div[1]/div[2]/dl[8]/dd/span[1]";
            String upText = safeInnerText(page, upXpath);
            row.put("up_count", parseInteger(upText));

            // 下跌家数
            String downXpath = (plateType == 6)
                    ? "html/body/div[2]/div[3]/div[2]/div/div[2]/div[1]/div[2]/dl[8]/dd/span[2]"
                    : "/html/body/div[2]/div[2]/div[2]/div/div/div[1]/div[2]/dl[8]/dd/span[2]";
            String downText = safeInnerText(page, downXpath);
            row.put("down_count", parseInteger(downText));

            // 领涨股（取第一个 tr 的股票名+代码）— 概念页在 #maincont 表格
            try {
                Locator firstStock = page.locator("#maincont table tbody tr:first-child td:eq(2) a").first();
                if (firstStock.count() > 0) {
                    row.put("lead_stock_name", firstStock.innerText());
                }
                Locator firstCode = page.locator("#maincont table tbody tr:first-child td:eq(1) a").first();
                if (firstCode.count() > 0) {
                    row.put("lead_stock_code", firstCode.innerText());
                }
            } catch (Exception e) {
                log.debug("[ThsPlateCrawler] 领涨股提取失败: {}", e.getMessage());
            }

            if (index % 50 == 0) {
                log.info("[ThsPlateCrawler] 进度: {}/{}, plate={}", index + 1, total, ref.name);
            }
            return row;

        } catch (Exception e) {
            log.warn("[ThsPlateCrawler] 详情页抓取失败: {}, error={}", ref.url, e.getMessage());
            return null;
        } finally {
            page.close();
        }
    }

    // ==================== 工具方法 ====================

    /** 安全取元素 innerText，不存在返回 null */
    private String safeInnerText(Page page, String xpath) {
        try {
            Locator loc = page.locator("xpath=" + xpath).first();
            if (loc.count() > 0) {
                String text = loc.innerText();
                return text != null && !text.isBlank() ? text.trim() : null;
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    /** 解析数字（去掉逗号、空格） */
    private BigDecimal parseDecimal(String text) {
        if (text == null || text.isBlank()) return null;
        try {
            String cleaned = text.replaceAll("[,\\s]", "");
            return new BigDecimal(cleaned);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** 解析百分比（"1.23%" → 1.23） */
    private BigDecimal parsePercent(String text) {
        if (text == null || text.isBlank()) return null;
        try {
            String cleaned = text.replace("%", "").replaceAll("[,\\s]", "");
            return new BigDecimal(cleaned);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** 解析"亿"单位（"123.45亿" → 12345000000） */
    private BigDecimal parseYi(String text) {
        if (text == null || text.isBlank()) return null;
        try {
            String cleaned = text.replace("亿", "").replaceAll("[,\\s]", "");
            BigDecimal yi = new BigDecimal(cleaned);
            return yi.multiply(new BigDecimal("100000000"));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** 解析"万手"单位（"1234.56万手" → 12345600 手） */
    private BigDecimal parseWanShou(String text) {
        if (text == null || text.isBlank()) return null;
        try {
            String cleaned = text.replace("万手", "").replace("手", "").replaceAll("[,\\s]", "");
            BigDecimal wan = new BigDecimal(cleaned);
            return wan.multiply(new BigDecimal("10000"));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** 解析整数 */
    private Integer parseInteger(String text) {
        if (text == null || text.isBlank()) return null;
        try {
            String cleaned = text.replaceAll("[,\\s]", "");
            return Integer.parseInt(cleaned);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** 板块引用（名称 + 详情页 URL） */
    private record PlateRef(String name, String url) {
    }
}
```

### 9.3 TonghuashunBrowserStrategy 路由扩展

在 `TonghuashunBrowserStrategy.fetch()` 中增加 `THS_PLATE` 路由：

```java
// TonghuashunBrowserStrategy.java — fetch() 方法内增加
@Override
public CrawlResult fetch(CrawlContext ctx) {
    CrawlTask task = ctx.getTask();
    Map<String, Object> params = JsonCheckpoint.deserialize(task.getParamsJson());
    String taskType = task.getTaskType();

    // THS_PLATE 路由到 ThsPlateCrawler
    if ("THS_PLATE".equals(taskType)) {
        return fetchThsPlate(ctx, params);
    }

    // ... 原有逻辑（url 必填 + extract 抽取）
}

private CrawlResult fetchThsPlate(CrawlContext ctx, Map<String, Object> params) {
    int plateType = parseInt(params.get("plate_type"), 6);
    String tradeDate = String.valueOf(params.getOrDefault("tradeDate",
            java.time.LocalDate.now().toString()));

    rateLimiter.acquire();
    try {
        List<Map<String, Object>> rows = thsPlateCrawler.crawl(plateType, tradeDate, antiCrawlConfig);
        CrawlResult result = new CrawlResult();
        result.setSuccess(true);
        result.setData(rows);
        result.setRowCount(rows.size());
        result.setHttpStatus(200);
        return result;
    } catch (Exception e) {
        throw new RuntimeException("THS_PLATE crawl failed: " e.getMessage(), e);
    }
}
```

并在构造函数注入 `ThsPlateCrawler`：

```java
private final ThsPlateCrawler thsPlateCrawler;

public TonghuashunBrowserStrategy(AntiCrawlConfig antiCrawlConfig, BrowserPool browserPool,
                                   ThsPlateCrawler thsPlateCrawler) {
    this.antiCrawlConfig = antiCrawlConfig;
    this.browserPool = browserPool;
    this.thsPlateCrawler = thsPlateCrawler;
    this.rateLimiter = new RateLimiter(antiCrawlConfig.getRateLimitPerSec());
}
```

### 9.4 验证命令

```bash
# 1. 登录拿 cookie（一次性，半自动过滑块）
java -cp crawler-strategy/target/classes \
  com.dunwugudao.crawler.strategy.tonghuashun.TonghuashunLogin <username> <password>

# 2. 下发 THS_PLATE 任务（3 个：地域/行业/概念）
curl -X POST "http://localhost:8081/api/job/seed-ths-plate" \
  -d "date=2026-08-09&source=0"

# 3. 查看任务进度
curl "http://localhost:8081/api/monitor/health"

# 4. 查 CK 数据
# SELECT plate_type, count() FROM ths_plate WHERE trade_date = '2026-08-09' GROUP BY plate_type
# 预期：plate_type=4 → ~33, plate_type=5 → ~76, plate_type=6 → ~375
```

### 9.5 注意事项

1. **概念页"显示全部"是核心**：不点这个按钮只能抓到 20 个概念，`clickShowAll()` 用多选择器兜底
2. **单任务串行**：375 个概念逐个打开详情页，单线程约 15-25 分钟，**不要并发**（BrowserPool 单实例）
3. **礼貌延迟**：每个详情页之间 800-1500ms 随机延迟，避免触发反爬
4. **Cookie 前置**：必须先跑 `TonghuashunLogin` 拿到有效 cookie，否则列表页可能返回空或验证码
5. **DOM 改版风险**：所有 xpath 基于对标项目实测，同花顺随时可能改 DOM，需端到端验证 + 多选择器兜底
6. **失败容错**：单个板块详情页失败不影响整体，日志记录后继续下一个
