# 日批编排:阶段 → URL → CK 表 对照表

## 总览(13 阶段(12 主 + 1 链式),按编排顺序)

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
| 13 | DRAGON_TIGER_DETAIL | datacenter 按 trade_id(链式) | `dt_detail` | ts_code, trade_date, seat_name, seat_type | 无(随席位数) |

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

---

## 链式阶段(依赖前置阶段落库后执行)

### 13. DRAGON_TIGER_DETAIL — 龙虎榜席位明细
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
