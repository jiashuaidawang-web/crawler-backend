# board_daily 端到端推导与修改记录

> 时间：2026-08-03
> 目标：验证并修复 board_daily（板块日线）的端到端链路，作为 STOCK_DAILY 之后的第一批市场级任务。

---

## 1. 起点：测试顺序的重新排列

原计划按 dailySeed 下发顺序（STOCK_DAILY 之后是市场级）。改为**按测试覆盖面/验证价值**排序：

| 顺序 | 表 | taskType | 解析器 | 验证价值 |
|---|---|---|---|---|
| ✅ 已测 | stock_daily | STOCK_DAILY | CLIST | OkHttp 主路径 + 重试换 IP |
| 2 | board_daily | BOARD_DAILY | CLIST | 同 CLIST 解析器、市场级（expected_count=null） |
| 3 | limit_up_pool | LIMIT_UP | ZT_POOL | ZT_POOL 解析器 + Playwright fallback |
| ... | ... | ... | ... | ... |

**结论**：先测 board_daily（同 CLIST 解析器、市场级、失败成本低）。

---

## 2. 第一轮核实：BOARD_DAILY 端到端现状

逐层检查 worker 侧（EastmoneyApiStrategy → Endpoints → Parsers → DedupWriter）：

| 层 | 现状 | 状态 |
|---|---|---|
| Worker 分派 | EastmoneyApiStrategy OkHttp + CLIST 解析器，能处理 | ✅ |
| Endpoint | `push2 clist/get`，fs=`m:90+t:2,m:90+t:3,m:90+t:1`（行业+概念+地域合并拉） | ✅ |
| Parser | parseClist 有 BOARD_DAILY 分支 | ⚠️ 有 bug |
| 落库 | DedupWriter.writeBoardDaily → board_daily | ❌ 必失败 |
| 量校验 | expectedCount=null → VolumeValidator 直接放行 | ✅ |
| **Seeding** | **不在 TaskTypeCatalog.ALL 里，dailySeed 永远不产出** | ❌ |

### 发现的两处真实 bug

1. **`board_daily.board_type` NOT NULL 违反（硬伤）**
   - schema: `board_type SMALLINT NOT NULL`（schema-update-fields.sql:15）
   - parser 写 `row.put("board_type", parseInt(params.get("boardType")))` —— 但 BOARD_DAILY 的 params 是 `{"tradeDate":"..."}`，**没有 boardType** → 写 null → 数据库拒绝整批

2. **BOARD_DAILY 没进 TaskTypeCatalog**
   - worker 侧三处都完备（Endpoints/Parsers/Writer），但 seeding 侧缺位，只能靠 SeedController 手动单条下发

---

## 3. 第二轮核实：CLIST 解析器是什么

**CLIST = 东财 `push2.eastmoney.com/api/qt/clist/get` 接口的通用响应解析器。**

东财大量行情/列表接口共用同一个 `clist/get` 入口，返回结构统一为 `data.diff[]`（每行一个 JSON 对象，字段是 `f12`/`f14`/`f3` 这类无意义编号）。不同 taskType 只是 **fs 筛选集 + fields 投影** 不同，响应骨架完全一样。

CLIST 解析器把这个统一骨架翻译成"目标表列名"：

```
原始响应（f 编号）      解析后（schema 列名）
f12  "BK0450"       →  board_code
f14  "半导体"        →  board_name
f3   2.31           →  pct_chg
f62  1.2e9          →  main_net
f166 "688001"       →  leading_code   ← 后被纠正为 f140
```

- 实现在 `EastmoneyParsers.parseClist(...)`（EastmoneyParsers.java:22）
- 内部 `switch(taskType)` 分支：同一份 diff 数据，按 BOARD/MAIN_FUND/STOCK_DAILY 等产出不同的列名映射

---

## 4. 第三轮核实：board_basic 的角色

**关键认知纠正**：board_basic 不是"从 board_daily 聚合出来的"，而是一个**独立的维表**。

### board_basic 的真实数据来源

```
BoardUniverseProvider.boardInfos()
  → 按 地域/行业/概念 各拉一次 push2 clist/get
  → 写 board_basic（board_code, board_name, board_type, status）
```

**调用时机**：`dailySeed` 第 1 步就是 `boardBasicService.maintain()`（SeedGenerator.java:73），在所有行情任务之前。

### board_basic 用的请求 URL

URL 模板（两个方法同一模板，只 fields 不同）：
```
https://push2.eastmoney.com/api/qt/clist/get?pn={page}&pz=100&po=1&np=1&fltt=2&invt=2&fid=f3&fs={fs}&fields={fields}
```

实际请求（maintain 只调 boardInfos，3 次调用）：

| fs（板块筛选） | fields | 用途 |
|---|---|---|
| `m:90+t:1+f:!50`（地域） | `f12,f14` | 代码+名称+类型 |
| `m:90+t:2+f:!50`（行业） | `f12,f14` | 代码+名称+类型 |
| `m:90+t:3+f:!50`（概念） | `f12,f14` | 代码+名称+类型 |

**注意**：board_basic 的请求带 `fid=f3`，board_daily 的请求（EastmoneyEndpoints 里）没有。

### 真正的依赖链

```
board_basic（维表）         board_daily（行情）
     │                          │
     │ 数据来源：                │ 数据来源：
     │ BoardUniverseProvider    │ BOARD_DAILY 接口
     │ 直接拉板块列表            │ 一次拉三类板块行情
     │                          │
     ↓                          ↓
  STOCK_BY_BOARD ←──────────────┘
  （逐板块个股，读 board_basic）
```

board_basic 和 board_daily **没有生成依赖**——它们从不同接口来。但 board_basic 是 STOCK_BY_BOARD 的前置。

---

## 5. 用户纠正：board_daily 分三类、真实字段映射

**用户纠正了我的多个错误假设**，并给出真实接口数据和字段映射：

### board_daily 分三个独立请求（地域/行业/概念）

| 类型 | 数据量 | fs | 是否分页 |
|---|---|---|---|
| 地域 | ~31 | `m:90+t:1+f:!50` | 否（1 页） |
| 行业 | ~496 | `m:90+t:2+f:!50` | 是（5 页） |
| 概念 | ~503 | `m:90+t:3+f:!50` | 是（6 页） |

### 纠正的关键字段映射

| 数据库字段 | 东财 JSON 字段 | 我之前错误的假设 |
|---|---|---|
| board_type | **来自 taskType 映射**（非响应字段） | ❌ 以为读 params.boardType |
| leading_code | **f140** | ❌ 以为是 f166 |
| leading_name | **f128** | ❌ 以为是 f167 |
| limit_up_count | ❌ 接口未返回 | — |
| board_code2 | ❌ 接口未返回 | — |

### board_type 怎么取

f13 **不是**板块类型标识（是市场码），响应里**没有**能区分地域/行业/概念的字段。所以 board_type 只能从 **taskType 本身** 推断：

| taskType | board_type |
|---|---|
| REGION_DAILY | 1 |
| INDUSTRY_DAILY | 2 |
| CONCEPT_DAILY | 3 |

parser 里 `row.put("board_type", taskTypeToBoardType(spec.getTaskType()))` —— 靠 taskType 映射，不靠响应字段。

---

## 6. 最终方案：board_basic 改为 board_daily 同步的副作用

### 核心设计转变

**board_basic 从"独立维护步骤"降级为"board_daily 同步的副作用"**——两者共用同一套 clist 接口，board_daily 拉到某板块行情时，顺手看 board_basic 有没有它，没有就新增。

这样 dailySeed 第 1 步的 `boardBasicService.maintain()` 不再需要。

### 设计取舍

- **只增不删**（board_basic 是维表，删除由独立逻辑决定，不耦合在同步里）
- **名称变化不更新**（保持简单，board_basic 名称不是关键字段）
- **幂等**（重复调用不产生重复数据，按三字段唯一查询）
- **失败不抛**（调用方 board_daily 不受影响）

### 持久化校验口径

按 `(board_type, board_code, data_source)` 三字段组合查询，有则跳过、无则新增。比单纯按 board_code 查更准（同一代码在不同 board_type 下是不同板块）。

---

## 7. 修改清单（5 处，最小外科手术）

### 改 1：EastmoneyParsers.java —— 修正字段映射

BOARD_DAILY/REGION/INDUSTRY/CONCEPT_DAILY 分支：
- `board_type` → `taskTypeToBoardType(spec.getTaskType())`（不读 params.boardType）
- `leading_code` → f140（不是 f166）
- `leading_name` → f128（不是 f167）
- `limit_up_count` / `board_code2` → null
- 加私有辅助方法 `taskTypeToBoardType`

### 改 2：EastmoneyEndpoints.java —— 修正 fields 投影

BOARD/REGION/INDUSTRY/CONCEPT 分支的 defaultFields：
```
f12,f14,f2,f3,f4,f5,f6,f7,f8,f10,f15,f16,f17,f18,f20,f21,f62,f104,f105,f140,f128
```
（把 f166,f167 换成 f140,f128）

### 改 3：新建 BoardBasicSyncService.java

放在 `crawler-persistence/src/main/java/com/dunwugudao/crawler/persistence/service/`：
- `syncBoard(boardCode, boardName, boardType, dataSource)` —— 单条幂等同步
- 按 `(board_type, board_code, data_source)` 三字段查（QueryWrapper，无 XML）
- 有则跳过，无则新增
- 失败只 warn 不抛

### 改 4：DedupWriter.java —— 注入 + 调用

- 注入 `BoardBasicSyncService`
- `writeBoardDaily` 里每写一行后调 `syncBoard(boardCode, boardName, boardType, source.getCode())`
- 失败只 warn

### 改 5：SeedGenerator.java —— 删 maintain 调用

- 删 dailySeed 第 1 步的 `boardBasicService.maintain()` 调用
- 删 `boardBasicService` 注入（boardBasicMapper 保留给 seedByBoard）

### 不动的（现有已满足）

- Catalog（REGION/INDUSTRY/CONCEPT_DAILY 已在，带 boardType=1/2/3）
- 自动翻页（CLIST 分支 while 循环，行业 496→5 页、概念 503→6 页自动处理）
- buildUrl（fs 单一类型）
- VolumeValidator（null 放行）
- Worker 失败换 IP + 日志记 IP（fetchWithWorkerProxy 已实现）

---

## 8. 验证方式

通过 SeedController 单条触发 REGION_DAILY 验证端到端：

```bash
curl -s -X POST http://localhost:8081/api/crawl/seed \
  -H "Content-Type: application/json" \
  -d '{
    "taskType": "REGION_DAILY",
    "source": 1,
    "tradeDate": "2026-08-01",
    "paramsJson": "{\"boardType\":1,\"tradeDate\":\"2026-08-01\"}",
    "expectedCount": null,
    "priority": 5,
    "maxRetry": 3
  }'
```

**预期**：
- 返回 `{"taskId":..., "status":"PENDING", "inserted":1}`
- worker 日志：`process task=..., type=REGION_DAILY` → `fetchWithWorkerProxy success, proxy=<IP>, url=...&fs=m:90+t:1+f:!50&...` → `write ok` → `complete ok`
- DB 验证：

```sql
-- board_daily 应有地域板块数据（约 31 行），board_type=1
SELECT board_type, COUNT(*) FROM board_daily WHERE trade_date='2026-08-01' GROUP BY board_type;

-- board_basic 应同步新增（副作用），board_type=1
SELECT board_type, COUNT(*) FROM board_basic WHERE board_type=1 GROUP BY board_type;
```

---

## 9. 关键认知总结

1. **BOARD_DAILY（合并拉）和 REGION/INDUSTRY/CONCEPT_DAILY（分开拉）是两套重叠方案**。选后者才能拿到 board_type。
2. **board_type 只能从 taskType 来**——响应里没有能区分地域/行业/概念的字段（f13 是市场码，不是板块类型）。
3. **board_basic 作为 board_daily 同步的副作用**，是更简洁的设计（避免独立维护步骤，且天然带 board_type）。
4. **CLIST 解析器是"一种响应、多种语义"翻译器**——同一 `data.diff[]` 骨架，靠 taskType 分支产出不同列名映射。
5. **外科手术式修改**：5 处改动、不碰重叠方案、不加重构。
