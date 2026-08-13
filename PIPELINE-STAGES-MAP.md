# 日批编排:阶段 → URL → CK 表 对照表

## 总览(16 阶段(14 主 + 1 链式 + 1 周级),按编排顺序)

| # | PipelineStage | 抓取 URL(东财) | 写入 CK 表 | 自然键(去重) | 上游总数来源 |
|---|--------------|---------------|-----------|------------|------------|
| 1 | STOCK_DAILY | push2 clist(分页) | `stock_daily` | ts_code, trade_date, data_source | data.total |
| 2 | REGION_DAILY | push2 clist(分页) | `board_daily` | board_code, trade_date | data.total |
| 3 | INDUSTRY_DAILY | push2 clist(分页) | `board_daily` | board_code, trade_date | data.total |
| 4 | CONCEPT_DAILY | push2 clist(分页) | `board_daily` | board_code, trade_date | data.total |
| 5 | MAIN_FUND_STOCK | push2 clist(分页) | `main_fund_flow` | obj_type, ts_code, board_code, index_code, trade_date, data_source | data.total |
| 6 | MAIN_FUND_BOARD | push2 clist(分页) | `main_fund_flow` | obj_type, ts_code, board_code, index_code, trade_date, data_source | data.total |
| 7 | LIMIT_POOL | push2ex 3池(分页) | `limit_up_pool` / `limit_down_pool` / `zhaban_pool` | ts_code, trade_date, data_source | data.tc(各池) |
| 8 | STRONG_POOL | push2ex(分页) | `strong_pool` | ts_code, trade_date, data_source | data.tc |
| 9 | CIXIN_POOL | push2ex(分页) | `cixin_pool` | ts_code, trade_date, data_source | data.tc |
| 10 | NORTHBOUND | push2 kamt(实时) | `northbound_flow` | trade_date | data.s2n 数组 size |
| 11 | INDEX_DAILY | push2 clist(单页) | `index_daily` | index_code, trade_date | data.total |
| 12 | DRAGON_TIGER | datacenter(单页) | `dragon_tiger` | ts_code, trade_date, reason | result.count |
| 13 | BOARD_BASIC | push2 clist 3类(单页) | `board_basic` | board_type, board_code, data_source | data.total(3类) |
| 14 | STOCK_BY_BOARD | push2 clist 按板块(分页) | `stock_board_rel` | board_code, ts_code, board_type, data_source | 无(按板块探测) |
| 15 | STOCK_WEEKLY | 从 stock_weekly 聚合(周级) | `stock_weekly` | ts_code, trade_date, data_source | 无(聚合) |
| 16 | DRAGON_TIGER_DETAIL | datacenter 按 trade_id(链式) | `dt_detail` | ts_code, trade_date, seat_name, seat_type | 无(随席位数) |

---

## 各阶段详细说明

### 1. STOCK_DAILY — 个股日线
- **URL**: `https://push2.eastmoney.com/api/qt/clist/get` (按页拆分,每页 100 条)
- **参数**: `fs` 含沪深主板+创业板+科创板,`pz=100`,`pn={页}`
- **探测**: `fetchClistTotalByProxy` → `data.total`(全市场股票数)
- **CK 表**: `stock_daily` (MergeTree,ORDER BY (board_code, trade_date))
- **自然键**: `ts_code, trade_date, data_source`
- **校验过滤**: `WHERE trade_date=? AND data_source=?`

### 2. REGION_DAILY — 地域板块日线
- **URL**: `http://push2.eastmoney.com/api/qt/clist/get`(clist,地域板块)
- **探测**: `data.total`
- **CK 表**: `board_daily`
- **校验过滤**: `WHERE trade_date=? AND data_source=? AND board_type=1`

### 3. INDUSTRY_DAILY — 行业板块日线
- **URL**: 同上 clist
- **CK 表**: `board_daily`
- **校验过滤**: `AND board_type=2`

### 4. CONCEPT_DAILY — 概念板块日线
- **URL**: 同上 clist
- **CK 表**: `board_daily`
- **校验过滤**: `AND board_type=3`

### 5. MAIN_FUND_STOCK — 个股主力资金流
- **URL**: `https://push2.eastmoney.com/api/qt/clist/get`(fflow)
- **CK 表**: `main_fund_flow`
- **校验过滤**: `AND obj_type='stock'`

### 6. MAIN_FUND_BOARD — 板块主力资金流
- **URL**: 同上 clist
- **CK 表**: `main_fund_flow`
- **校验过滤**: `AND obj_type='board'`

### 7. LIMIT_POOL — 涨停/跌停/炸板池(3 子类型)
- **URL**:
  - 涨停: `http://push2ex.eastmoney.com/getTopicZTPool`
  - 跌停: `http://push2ex.eastmoney.com/getTopicDTPool`
  - 炸板: `http://push2ex.eastmoney.com/getTopicZBPool`
- **探测**: `fetchPoolTotalByProxy` → `data.tc`(各池总数)
- **CK 表**: `limit_up_pool` / `limit_down_pool` / `zhaban_pool`
- **自然键**: `ts_code, trade_date, data_source`
- **校验**: 当前仅验 `limit_up_pool`(首版限制)

### 8. STRONG_POOL — 强势池
- **URL**: `http://push2ex.eastmoney.com/getTopicQSPool`
- **CK 表**: `strong_pool`

### 9. CIXIN_POOL — 次新池
- **URL**: `http://push2ex.eastmoney.com/getTopicCXPooll`(注意末尾双 l)
- **CK 表**: `cixin_pool`

### 10. NORTHBOUND — 北向资金
- **URL**: `http://push2.eastmoney.com/api/qt/kamt.rtmin/get`(实时端点,返回当日分钟级)
- **探测**: `fetchNorthboundCount` → `data.s2n` 数组 size(盘中约 241)
- **CK 表**: `northbound_flow` (MergeTree,ORDER BY trade_date)
- **注意**: 实时端点,忽略日期参数,始终返回当日;历史不可回填

### 11. INDEX_DAILY — 指数日线
- **URL**: `http://push2.eastmoney.com/api/qt/clist/get`(`fs=b:MK0010`,43 只一次拿完)
- **探测**: `fetchClistTotalByProxy` → `data.total`
- **CK 表**: `index_daily`
- **自然键**: `index_code, trade_date`(无 data_source)

### 12. DRAGON_TIGER — 龙虎榜主表
- **URL**: `https://datacenter-web.eastmoney.com/api/data/v1/get`(`reportName=RPT_DAILYBILLBOARD_DETAILSNEW`)
- **探测**: `fetchDragonTigerCount` → `result.count`(pageSize=1 即得真值)
- **CK 表**: `dragon_tiger`
- **自然键**: `ts_code, trade_date, reason`

### 13. BOARD_BASIC — 板块基础维表
- **URL**: `push2 clist`(3 类板块,每类单页 cap 10 页)
  - REGION_BOARD 地域板块
  - INDUSTRY_BOARD 行业板块
  - CONCEPT_BOARD 概念板块
- **探测**: `data.total`(各类分别探测)
- **CK 表**: `board_basic`(`ReplacingMergeTree(_ver)`,**无 trade_date 列**)
- **自然键**: `board_type, board_code, data_source`
- **说明**: 板块维表(代码/名称/特征),worker 落 writeBoardBasic

### 14. STOCK_BY_BOARD — 板块-个股关联
- **URL**: `push2 clist`(按板块,每板块先探测 total 再按页拆分)
- **CK 表**: `stock_board_rel`(`ReplacingMergeTree(_ver)`)
- **自然键**: `board_code, ts_code, board_type, data_source`
- **优化**: 对比今日 vs 昨日股票数+板块数,相同→跳过省 IP(详见下方"优化"节)

---

## 周级阶段(仅每周指定日跑,默认周六,配置 crawler.pipeline.weekly-day-of-week)

### 15. STOCK_WEEKLY — 周K(从日K聚合)
- **触发**: 每周指定日(默认周六)跑,**非每日**
- **逻辑**: `aggregateAllWeekly` 从 `stock_daily` 按 ts_code 聚合到 `stock_weekly`(补全扩展字段:振幅/量比/均价/主力净流入/PE/领涨股/行业/概念/市场)
- **CK 表**: `stock_weekly`(`MergeTree`)
- **自然键**: `ts_code, trade_date, data_source`
- **说明**: 聚合类任务(非抓取),无上游总数,校验仅做基础量

---

## 链式阶段(依赖前置阶段落库后执行)

### 16. DRAGON_TIGER_DETAIL — 龙虎榜席位明细
- **触发**:主阶段 DRAGON_TIGER 完成后,`chainDragonTigerDetails` 从 `dragon_tiger` 表读 trade_ids → 每个 trade_id 发一个明细任务
- **URL**: `https://datacenter-web.eastmoney.com/api/data/v1/get`(`reportName=RPT_BILLBOARD_SEAT`,`filter=(TRADE_ID={id})`)
- **CK 表**: `dt_detail`
- **自然键**: `ts_code, trade_date, seat_name, seat_type`
- **特点**:无独立上游总数(明细行数随席位数波动),以实际下发任务数为期;校验仅做基础量
- **编排位置**:主 12 阶段跑完后自动执行

---

## 校验时 CK 查询的精确过滤

```sql
-- STOCK_DAILY / 池子 / DRAGON_TIGER
SELECT count() FROM {table} WHERE trade_date = ? AND data_source = ?

-- board_daily(带 board_type)
SELECT count() FROM board_daily WHERE trade_date = ? AND data_source = ? AND board_type = ?

-- main_fund_flow(带 obj_type)
SELECT count() FROM main_fund_flow WHERE trade_date = ? AND data_source = ? AND obj_type = ?
```

> 注意:目标表均为 **MergeTree**(非 ReplacingMergeTree),不支持 `FINAL` 关键字,用普通 `count()`。

---

## 上游总数探测字段

| 接口响应路径 | 适用阶段 | 说明 |
|------------|---------|------|
| `data.total` | STOCK_DAILY / REGION / INDUSTRY / CONCEPT / MAIN_FUND / INDEX | clist 标准总数 |
| `data.tc` | LIMIT / STRONG / CIXIN_POOL | 池子专用总数字段 |
| `result.count` | DRAGON_TIGER | datacenter 信封层总数 |
| `data.s2n.size()` | NORTHBOUND | kamt 分钟级数组长度 |

---

## 关键文件

| 文件 | 作用 |
|------|------|
| `crawler-admin/.../pipeline/PipelineStage.java` | 阶段枚举 + 顺序 + 失败策略 |
| `crawler-admin/.../pipeline/DailyPipelineOrchestrator.java` | 编排器(seed→等待→校验→告警) |
| `crawler-admin/.../pipeline/TotalCountValidator.java` | 上游总数真值校验 |
| `crawler-admin/.../pipeline/StageSeeder.java` | 阶段→seed 方法路由 |
| `crawler-admin/.../seed/SeedGenerator.java` | 各阶段 seed*Result 方法(取上游总数) |
| `crawler-strategy/.../eastmoney/EastmoneyEndpoints.java` | URL 模板 |
| `crawler-persistence/.../DedupWriter.java` | taskType→CK 表写入路由 |
| `crawler-persistence/.../DedupService.java` | REGISTRY(自然键+引擎) |
| `crawler-web/src/views/PipelineDashboard.vue` | 前端阶段流程图 |

## 优化:STOCK_BY_BOARD 跳过逻辑(省 IP)

**逻辑**:对比今日 vs 昨日**股票数 + 板块数**,两者均相同 → 关联关系不会变化 → **跳过不发任务,省 IP**。

**实现**:
1. `StageSeeder.seedBoardRel`:
   - 查昨日数量:从昨日 `STOCK_BY_BOARD` 阶段的 `check_result` JSON 解析 `stockCount` + `boardCount`
   - 查今日数量:`stockGenerator.queryStockCodeCount`(CK stock_daily 去重 ts_code) + `queryBoardCodeCount`(board_basic 去重 board_code)
   - 调 `seedByBoardResult(今日股票,今日板块,昨日股票,昨日板块)`
2. `SeedGenerator.seedByBoardResult`:数量相同 → 返回 `SeedResult.empty("跳过省 IP")`;否则正常 seed
3. `DailyPipelineOrchestrator.storeBoardRelCounts`:跑完后存今日数量到 `check_result`,供明天比较

**效果**:大多数交易日股票/板块无变化 → 跳过大量按板块请求(每板块一次 HTTP + 分页),显著省 IP。

