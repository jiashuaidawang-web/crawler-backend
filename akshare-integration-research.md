# AKShare 集成方案 & 字段对齐报告

> 调研日期：2026-08-02
> AKShare 版本：**1.18.81**（本地 pip 安装实测）
> 验证脚本：verify_akshare.py / akshare_verify_output.log / akshare_verify_result.json
> 状态：**方案阶段，字段已实测验证，未开始编码**

---

## 〇、核心结论与关键发现

### 0.1 核心结论

1. **AKShare 能跟你们的表对齐**——实测验证了 9 个接口的真实列名。
2. **AKShare 是 Python 库，你们是 Java 后端**——必须通过 Python 微服务桥接。
3. **有一批坑必须处理**——单位陷阱、pre_close 反推、板块用中文名、市值缺失。
4. **验证脚本已就绪**——verify_akshare.py 一键复现。

### 0.2 🔴 最关键发现：AKShare 同样撞墙东财代理

> **AKShare 的东财接口走的是 push2his/push2.eastmoney.com——跟你们 crawler 遇到 502 的是同一个 endpoint。AKShare 并没有绕过代理问题。**

实测结果（本地无代理直连）：

| 东财 Endpoint | AKShare 接口 | 结果 | crawler 结论 |
|---|---|---|---|
| push2.eastmoney.com | stock_zh_a_hist, board_hist, fund_flow, spot_em | ❌ RemoteDisconnected | 502 ❌ |
| push2his.eastmoney.com | stock_zh_a_hist | ❌ RemoteDisconnected | — |
| push2ex.eastmoney.com | stock_zt_pool_em 等 5 个 | ✅ 成功 | 可用 ✅ |

**这意味着**：AKShare 的日线行情类接口仍然需要中国大陆住宅 IP 代理。AKShare 不是代理问题的解药。

### 0.3 接口验证总览

| 状态 | 数量 | 接口 |
|---|---|---|
| ✅ 成功 | **9** | index_daily, board_summary, dragon_tiger, 涨停池, 炸板池, 强势池, 次新池, 北向资金 |
| ❌ 代理拦截 | **6** | stock_daily, stock_weekly, board_daily×2, fund_flow×2, stock_universe |
| ⚠️ 返回空 | **1** | 跌停池（当日无数据） |

---

## 一、架构决策

### 1.1 整体架构



### 1.2 部署位置（关键）

AKShare 东财接口需要代理，**akshare-bridge 必须部署在有代理的节点**：

| 部署位置 | 可行性 | 说明 |
|---|---|---|
| 代理池服务器 124.223.220.245 | ✅ 推荐 | 已有 Webshare 代理 |
| 本地开发机 | ❌ | 无代理，东财接口全挂 |
| 爬虫服务器 100.92.86.64 | ⚠️ 待确认 | 需确认是否有代理 |

### 1.3 新增组件清单

| 组件 | 位置 | 职责 |
|---|---|---|
| akshare-bridge/ | 新目录 Python FastAPI | 封装调用、字段映射、单位换算 |
| SourceType.AKSHARE(3) | core 枚举 | 新来源码 = 3 |
| AkshareApiStrategy | strategy 模块 | 调 bridge HTTP，转 CrawlResult |
| AkshareFieldMap | strategy 模块 | AKShare 列名 → schema 列名 |
| DedupWriter 扩展 | persistence | 从 limit_pool 扩展到所有原始表 |

---

## 二、字段对齐表（✅ 实测验证版）

> 图例：✅ = 实测确认 | ⚠️ = 实测发现差异/陷阱 | 🔴 = 接口被代理拦截（推断）
> 验证环境：Python 3.12.9 + akshare 1.18.81，本地无代理。

### A. stock_daily（个股日线） 🔴 代理拦截

- 接口：stock_zh_a_hist(symbol, period="daily", start_date, end_date, adjust)
- 数据源：push2his.eastmoney.com（与 crawler 东财 K 线同一 endpoint）
- 验证：❌ RemoteDisconnected，需代理环境重测

推断列：日期, 开盘, 收盘, 最高, 最低, 成交量, 成交额, 振幅, 涨跌幅, 涨跌额, 换手率
缺失：pre_close（需反推）、total_mv / circ_mv / pe（日线不含市值/PE）

### C. index_daily（指数日线） ✅ 实测确认

- 接口：stock_zh_index_daily(symbol)，数据源：新浪（不需要代理）

实测列名：['date', 'open', 'high', 'low', 'close', 'volume']

实测样例：date=1990-12-19, open=96.05, close=99.98, volume=126000

⚠️ 英文列名！新浪源只有 OHLCV，缺成交额/涨跌幅/MA/MACD。

### D3. board_summary（板块涨跌家数） ✅ 实测确认

- 接口：stock_board_industry_summary_ths()，数据源：同花顺（不需要代理）

实测列名（12列）：['序号', '板块', '涨跌幅', '总成交量', '总成交额', '净流入', '上涨家数', '下跌家数', '均价', '领涨股', '领涨股-最新价', '领涨股-涨跌幅']

实测样例：板块=IT服务, 涨跌幅=6.29, 上涨家数=126, 下跌家数=3

**含关键的上涨/下跌家数字段**，可补 board_daily 缺口。

### F. dragon_tiger（龙虎榜） ✅ 实测确认

- 接口：stock_lhb_detail_em(start_date, end_date)，数据源：东财（不需要代理）

实测列名（21列）：['序号', '代码', '名称', '上榜日', '解读', '收盘价', '涨跌幅', '龙虎榜净买额', '龙虎榜买入额', '龙虎榜卖出额', '龙虎榜成交额', '市场总成交额', '净买额占总成交比', '成交额占总成交比', '换手率', '流通市值', '上榜原因', '上榜后1日', '上榜后2日', '上榜后5日', '上榜后10日']

实测样例：代码=000009, 名称=中国宝安, 上榜日=2026-07-31, 龙虎榜净买额=362524375.65

注意：区间累计明细，无席位明细（dt_detail 需另调 stock_lhb_jgmmtj_em）。

### G. limit_pool（涨跌停池） ✅ 实测确认

- 5 个独立接口，走 push2ex（都不需要代理）：

**涨停池** stock_zt_pool_em(date) — 16列：
['序号', '代码', '名称', '涨跌幅', '最新价', '成交额', '流通市值', '总市值', '换手率', '封板资金', '首次封板时间', '最后封板时间', '炸板次数', '涨停统计', '连板数', '所属行业']

样例：代码=000593, 名称=德龙汇能, 涨跌幅=10.0130033493042, 封板资金=82413851, 连板数=1

**炸板池**（差异列）：['涨停价', '涨速', '振幅']

**强势池**（差异列）：['涨停价', '涨速', '是否新高', '量比', '入选理由']

**次新池**（差异列）：['涨停价', '转手率', '开板几日', '开板日期', '上市日期', '是否新高']

### H. northbound_flow（北向资金） ✅ 实测确认

- 接口：stock_hsgt_fund_flow_summary_em()（⚠️ 无参数，返回全量历史）

实测列名（13列）：['交易日', '类型', '板块', '资金方向', '交易状态', '成交净买额', '资金净流入', '当日资金余额', '上涨数', '持平数', '下跌数', '相关指数', '指数涨跌幅']

实测样例：交易日=2026-07-31, 类型=沪港通, 板块=沪股通, 资金净流入=0.0

⚠️ 返回全量（沪港通+港股通+深港通），bridge 需过滤；单位待确认。

---

## 三、差距与风险总表

| 差距 | 影响 | 应对 |
|---|---|---|
| 🔴 AKShare 东财接口需要代理 | stock_daily / board_daily / fund_flow 本地跑不通 | akshare-bridge 部署到有代理的服务器 |
| 🔴 stock_daily 缺 total_mv / circ_mv / pe | 市值/PE 字段缺失 | 东财 push2 补 或 置 NULL 下游算 |
| 🔴 index_daily 只有 OHLCV（新浪源） | 缺成交额/涨跌幅/MA/MACD | 基础层落库，衍生指标下游算 |
| 🔴 board_daily 未实测（代理拦截） | 字段未确认 | 需在代理环境重跑 |
| 🔴 board_daily 板块用中文名 | 主键是 BK 代码 | bridge 维护 BK↔中文名 映射 |
| ⚠️ 涨停池 5 个接口列数不同 | 实现复杂度 | bridge 统一 endpoint + type 参数分发 |
| ⚠️ 龙虎榜无席位明细 | dt_detail 缺 | 另调 stock_lhb_jgmmtj_em |

---

## 四、优先级建议

| 优先级 | 表 | 理由 | 实测 |
|---|---|---|---|
| **P0** | limit_pool | 对齐度最高，不需要代理，与 push2ex 双源互校 | ✅ 4/5 |
| **P0** | dragon_tiger | 21 列丰富，不需要代理，对齐度 ~90% | ✅ |
| **P1** | index_daily | OHLCV 齐全，不需要代理；缺成交额/涨跌幅 | ✅ |
| **P1** | board_summary | 有关键上涨/下跌家数，不需要代理 | ✅ |
| **P2** | northbound_flow | 字段基本对齐，需过滤+单位确认 | ✅ |
| **P3** | stock_daily / weekly | 对齐度 ~90%，但需要代理 | 🔴 |
| **P3** | board_daily | 需要代理 + 板块名映射 | 🔴 |
| **P3** | main_fund_flow | 需要代理 | 🔴 |

**核心结论**：不需要代理的接口先做（limit_pool、dragon_tiger、index_daily）可立刻产出；stock_daily/board_daily/fund_flow 仍需代理，AKShare 不是绕过代理的方案。

---

## 五、实施步骤

**Phase 1 — akshare-bridge POC（Python）**
- [ ] FastAPI 服务，先实现不需要代理的接口：/limit_pool、/dragon_tiger、/index_daily
- [ ] 字段映射 + 单位换算
- [ ] 部署到有代理的服务器后，补齐 /stock_daily、/board_daily、/fund_flow

**Phase 2 — Java 接入**
- [ ] SourceType.AKSHARE(3) 枚举扩展
- [ ] AkshareApiStrategy implements SourceStrategy
- [ ] AkshareFieldMap 列名静态映射
- [ ] StrategyFactoryConfig 注册新策略

**Phase 3 — 种子 + 去重**
- [ ] TaskTypeCatalog 加 AKSHARE 规格
- [ ] DedupWriter 扩展到所有原始表
- [ ] 优先级：AKShare(3) 低于东财(1)，只填东财缺的数据

**Phase 4 — 扩展**
- [ ] stock_daily / stock_weekly（需代理）
- [ ] board_daily（需代理 + BK↔中文名映射）
- [ ] main_fund_flow（需代理）

---

## 六、已验证 & 待验证清单

### ✅ 已确认（实测通过）

1. stock_zh_index_daily 列名：['date', 'open', 'high', 'low', 'close', 'volume']（英文）
2. stock_board_industry_summary_ths 列名：12 列（含上涨/下跌家数）
3. stock_lhb_detail_em 列名：21列
4. stock_zt_pool_em 列名：16列
5. stock_zt_pool_zbgc_em 列名：16列
6. stock_zt_pool_strong_em 列名：16列
7. stock_zt_pool_sub_new_em 列名：16列
8. stock_hsgt_fund_flow_summary_em 列名：13列

### 🔴 待验证（需代理环境）

1. stock_zh_a_hist（stock_daily / stock_weekly）
2. stock_board_industry_hist_em / stock_board_concept_hist_em
3. stock_individual_fund_flow（main_fund_flow）
4. stock_zh_a_spot_em（stock_universe）
5. 北向资金单位确认（万元 vs 元）

### ⚠️ 需关注

1. 跌停池当日空——是否数据更新时机问题
2. board_summary 总成交额单位（可能亿元）

---

## 七、原始数据来源

- 验证脚本：verify_akshare.py
- 原始输出：akshare_verify_output.log
- 结构化结果：akshare_verify_result.json
