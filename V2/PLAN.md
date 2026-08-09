# V2 版本新增功能计划

## 优先级总览

| 优先级 | 模块 | 数据源 | 技术路线 |
|--------|------|--------|----------|
| **P0** | 同花顺板块体系 | 同花顺 | Playwright + cloak（验证可行性） |
| **P1** | 主力资金流（日/分）+ 板块资金流 | 东财 HTTP | 纯接口，复用现有框架 |
| **P1** | 活跃股分时量能 | 东财 HTTP | 纯接口，**单独大表**，只存活跃股 |
| **P2** | 个股分钟 K 线 / 指数分钟 | 东财 HTTP | 纯接口 |
| **P2** | 买卖五档 + 涨跌停价 | 东财 HTTP | 纯接口，盘中高频 |
| **P3** | 同花顺背离系数（PLZ） | 计算派生 | 基于 P0 数据计算 |

---

## P0：同花顺板块体系（先验证 Playwright + cloak）

### 目标

验证咱这套反检测浏览器能不能稳稳抓下同花顺的板块数据。**这是 V2 的前置门槛**——如果这一步走不通，后面同花顺相关的都不用想了。

### 两个子任务

#### P0-1：同花顺板块基础维表（ths_plate）

| 项 | 内容 |
|----|------|
| URL | `http://q.10jqka.com.cn/dy/`、`/thshy/`、`/gn/` |
| 关键操作 | 概念页面**必须先点 `/html/body/div[2]/div[2]/a`**（展开全部 375 个概念） |
| 抓取内容 | 板块名、板块代码、涨幅、领涨股 |
| 落表 | `ths_plate`（对标 `plate` 表，source=THS） |

#### P0-2：同花顺板块-个股关系（ths_stock_board_rel）

| 项 | 内容 |
|----|------|
| URL | `http://q.10jqka.com.cn/gn/detail/field/199112/order/desc/page/{p}/ajax/1/code/{plateCode}` |
| 关键操作 | 先浏览器访问板块主页**取 cookie**，再带 cookie 请求 AJAX；Jsoup 解析 tbody；按 `.page_info` 翻页 |
| 落表 | `ths_stock_board_rel`（对标 `stock_plate_relationships`） |

### 验证标准

- [ ] 地域 33 个、行业 76 个、概念 375 个全部稳定抓下
- [ ] 每个板块翻页到底，个股关联无遗漏
- [ ] 连续跑 3 天不触发反爬 / 不丢 cookie

---

## P1：东财主力资金 + 分时量能（核心数据）

### P1-1：个股主力资金流

| 项 | 日线 | 分钟线 |
|----|------|--------|
| URL | `push2his.../fflow/daykline?klt=101&secid={m}.{code}` | `push2.../fflow/kline?klt=1&secid={m}.{code}` |
| 返回 | klines 二维数组，每行 14 列 | 同左 |
| 字段 | 日期，主力/小/中/大/超大净流入 × 5，占比 × 5，收盘价，涨跌幅 | 同左 |
| 种子 | 盘后从 `stock_daily` 当日股票池循环 | 同左 |
| 落表 | `stock_capital_day` | `stock_capital_minute` |

### P1-2：板块资金流

| 项 | 内容 |
|----|------|
| URL | `push2his.../fflow/daykline?secid=90.{BKCODE}`（跟个股**同一接口**，secid 换成 `90.BK`） |
| 种子 | 从 `board_basic` 循环 |
| 落表 | `board_capital_day`（字段 + `board_code, board_name, board_type`） |

### P1-3：⭐ 活跃股分时量能（重点设计）

**数据量评估**：全市场 5000 只 × 240 分钟/天 = **120 万行/天**，太大。

**策略：只存"活跃股"**，定义（满足任一）：
- 当日涨停 / 跌停 / 炸板 / 强势 / 次新池中的股（5 个池子）
- 当日量比 > 3
- 当日换手率 > 15%

这样每天大概 **500~1500 只** × 240 分钟 = **12~36 万行/天**，可控。

| 项 | 内容 |
|----|------|
| URL | `push2his.../stock/trends2/get?secid={m}.{code}&ndays=1&iscr=1` |
| 返回 | `data.trends`：每行 `时间,开,收,高,低,量,额` |
| 落表 | `stock_minute_trend`（见下表设计） |

**表设计（CK MergeTree，按日期分区）**：

```sql
CREATE TABLE stock_minute_trend (
    trade_date  Date,
    symbol      String,
    stock_name  String,
    minute_time DateTime,        -- 精确到分钟
    open        Decimal64(4),
    close       Decimal64(4),
    high        Decimal64(4),
    low         Decimal64(4),
    volume      UInt64,          -- 手
    amount      Decimal64(2),    -- 元
    is_active   UInt8            -- 1=活跃股标记
) ENGINE = MergeTree()
PARTITION BY toYYYYMM(trade_date)
ORDER BY (symbol, minute_time);
```

**种子策略**：
- 先跑 P1-1 的主力资金 + 5 个池子
- 从结果中筛出活跃股列表
- 再下发 `MINUTE_TREND` 任务（只对这些股）

---

## P2：补充数据（东财 HTTP）

### P2-1：个股分钟 K 线（1/5/15/30/60 分钟）

| URL | `push2his.../stock/kline/get?secid={m}.{code}&klt={1|5|15|30|60}&fqt=0` |
|-----|-------|
| 用途 | 比分时量能更紧凑（OHLCV），适合技术分析 |
| 落表 | `stock_kline_minute`（复用现有 `stock_daily` 结构，加 `klt` 粒度字段） |

### P2-2：指数 / 板块分钟 K 线

| URL | `push2his.../stock/kline/get?secid={1.000001|0.399001|90.BKxxx}&klt=1` |
|-----|-------|
| 落表 | `index_kline_minute` / `board_kline_minute` |

### P2-3：买卖五档 + 涨跌停价（盘中高频）

| 项 | 内容 |
|----|------|
| URL | `69.push2.../stock/get?fields=f51,f52,f19..f20,f17..f18,f15..f16,f13..f14,f11..f12,f39..f40,f37..f38,f35..f36,f33..f34,f31..f32&secid={m}.{code}` |
| 字段 | f51=涨停价，f52=跌停价，f19~f11=买一~买五，f39~f32=卖一~卖五 |
| 场景 | 盘中实时，只对涨停池 / 监控股高频轮询 |
| 落表 | `stock_order_book`（可选，看是否需要留存） |

---

## P3：派生指标（计算型）

### P3-1：同花顺背离系数（PLZ）

- **不是爬的，是算的**：`PLZ = 个股涨幅 - 所属板块涨幅`
- 依赖 P0 的同花顺板块-个股关系 + 板块涨幅
- 落表 `stock_plate_deviation`
- 用途：发现"跑赢板块"的强势股

---

## 推进路线图

```
Week 1-2: P0 同花顺验证
  ├─ P0-1 ths_plate (地域/行业/概念)
  └─ P0-2 ths_stock_board_rel (板块-个股)
  → 里程碑：Playwright + cloak 跑通，数据稳定

Week 3-4: P1 主力资金 + 分时量能
  ├─ P1-1 stock_capital_day / minute
  ├─ P1-2 board_capital_day
  └─ P1-3 stock_minute_trend (活跃股)
  → 里程碑：主力资金 + 分时量能表建成

Week 5-6: P2 补充
  ├─ P2-1 个股分钟 K
  ├─ P2-2 指数/板块分钟 K
  └─ P2-3 买卖五档(可选)
  → 里程碑：分钟级数据完备

Week 7+: P3 派生
  └─ P3-1 背离系数
  → 里程碑：策略指标可用
```

---

## 除用户指定外，补充的内容

| 补充项 | 理由 |
|--------|------|
| **个股分钟 K 线（P2-1）** | 比 trends2 更紧凑，OHLCV 标准结构，技术分析直接能用 |
| **指数 / 板块分钟 K（P2-2）** | 跟个股分钟 K 同一接口，顺手就做了 |
| **买卖五档（P2-3）** | 做短线 / 涨停监控很有用，但数据量大，可选 |
| **背离系数（P3-1）** | 不算新爬的，但依赖同花顺板块数据，放最后 |

**控盘 / 主力成本**：砍掉，没参考价值。

---

## 风险提示

1. **P0 是最大风险**：同花顺反爬严，如果 cloak 扛不住，需要备选方案（比如降级到东财的板块-个股接口 `push2.../clist/get?fs=b:BKxxx`）
2. **P1-3 数据量**：活跃股筛选条件要可调，避免漏存或爆量
3. **主力资金接口频率**：每只股票一个请求，5000 只走代理要控并发，避免被封
