# 东财 URL → 入库表 映射总表

> 来源：`EastmoneyEndpoints.java`（crawler-strategy）+ 实测 URL 样例
> 每个东财 endpoint 按 `taskType` 路由到对应解析器，最终落入一张或多张表。

---

## 一、总览：Endpoint × Parser × Table

| # | taskType | 东财 Endpoint (baseUrl) | 解析器 | 入库表 | 数据源域名 | 需代理 |
|---|---|---|---|---|---|---|
| 1 | STOCK_DAILY | api/qt/stock/kline/get | KLINE | **stock_daily** | push2his.eastmoney.com | ✅ 需代理 |
| 2 | STOCK_WEEKLY | api/qt/stock/kline/get | KLINE | **stock_weekly** | push2his.eastmoney.com | ✅ 需代理 |
| 3 | INDEX_DAILY | api/qt/stock/kline/get | KLINE | **index_daily** | push2his.eastmoney.com | ✅ 需代理 |
| 4 | BOARD_DAILY | api/qt/clist/get | CLIST | **board_daily** | push2.eastmoney.com | ✅ 需代理 |
| 5 | MAIN_FUND_STOCK | api/qt/clist/get | CLIST | **main_fund_flow** | push2.eastmoney.com | ✅ 需代理 |
| 6 | MAIN_FUND_BOARD | api/qt/clist/get | CLIST | **main_fund_flow** | push2.eastmoney.com | ✅ 需代理 |
| 7 | DRAGON_TIGER | api/data/get | DATACENTER | **dragon_tiger** | datacenter-web.eastmoney.com | ✅ 需代理 |
| 8 | DRAGON_TIGER_DETAIL | api/data/get | DATACENTER | **dt_detail** | datacenter-web.eastmoney.com | ✅ 需代理 |
| 9 | LIMIT_POOL | getTopicZTPool 等 5 路径 | ZT_POOL | **limit_pool** | push2ex.eastmoney.com | ❌ 不需要 |
| 10 | STRONG_POOL | getTopicQSPool | ZT_POOL | **limit_pool** | push2ex.eastmoney.com | ❌ 不需要 |
| 11 | CIXIN_POOL | getTopicCXPooll | ZT_POOL | **limit_pool** | push2ex.eastmoney.com | ❌ 不需要 |

---

## 二、按 Endpoint 域名分组

### 2.1 push2his.eastmoney.com（KLINE — 历史 K 线）

**baseUrl**：`https://push2his.eastmoney.com/api/qt/stock/kline/get`

**URL 模板**：
```
https://push2his.eastmoney.com/api/qt/stock/kline/get
  ?secid={market}.{code}     // 1.600030（沪）/ 0.00001（深）/ 1.000001（指数）
  &klt={101|102}             // 101=日线, 102=周线
  &fqt=0                     // 不复权（0）/ 前复权（1）/ 后复权（2）
  &fields1=f1,f2,f3,f4,f5,f6
  &fields2=f51,f52,f53,f54,f55,f56,f57,f58,f59,f60,f61
  &end={YYYYMMDD}
  &lmt={条数}                 // 一次取足
```

**secid 映射**（指数 → market.code）：

| indexCode | secid |
|---|---|
| 000001.SH | 1.000001 |
| 399001.SZ | 0.399001 |
| 399006.SZ | 0.399006 |
| 000300.SH | 1.000300 |
| 000905.SH | 1.000905 |
| 000852.SH | 1.000852 |
| 932000.CSI | 1.932000 |

**按 taskType 路由**：

| taskType | 入库表 | 区分方式 |
|---|---|---|
| STOCK_DAILY | stock_daily | params.tsCode = 600000.SH，klt=101 |
| STOCK_WEEKLY | stock_weekly | params.tsCode = 600000.SH，klt=102 |
| INDEX_DAILY | index_daily | params.indexCode = 000001.SH |

**KLINE fields2 固定顺序**（逗号分隔字符串）：
```
f51=日期, f52=开盘, f53=收盘, f54=最高, f55=最低,
f56=成交量, f57=成交额, f58=振幅, f59=涨跌幅, f60=涨跌额, f61=换手率
```

**实测样例 URL**：
```
https://push2his.eastmoney.com/api/qt/stock/kline/get?secid=1.600030&klt=101&fqt=0
  &fields1=f1,f2,f3,f4,f5,f6&fields2=f51,f52,f53,f54,f55,f56,f57,f58,f59,f60,f61
  &end=20260802&lmt=1
```

---

### 2.2 push2.eastmoney.com（CLIST — 列表/板块/资金流）

**baseUrl**：`https://push2.eastmoney.com/api/qt/clist/get`

**URL 模板**：
```
https://push2.eastmoney.com/api/qt/clist/get
  ?pn={页码}&pz=200&po=1&np=1&fltt=2&invt=2
  &fs={过滤串}
  &fields={字段投影}
```

**按 taskType 路由**（fs 过滤串是路由关键）：

| taskType | 入库表 | fs 过滤串 | fields 投影 |
|---|---|---|---|
| BOARD_DAILY | board_daily | `m:90+t:2,m:90+t:3,m:90+t:1`（行业+概念+地域） | f12,f14,f3,f62,f104,f105 |
| MAIN_FUND_STOCK | main_fund_flow | `m:0+t:6,m:0+t:80,m:1+t:2,m:1+t:23`（个股列表） | f12,f13,f14,f62,f66,f72,f78,f84 |
| MAIN_FUND_BOARD | main_fund_flow | `m:90+t:2,m:90+t:3,m:90+t:1`（行业+概念+地域） | f12,f14,f62,f66,f72,f78,f84 |

**fs 参数速查**：

| fs 含义 | 说明 |
|---|---|
| m:0+t:6 | 沪深A股（深市主板+中小板+创业板） |
| m:0+t:80 | 创业板 |
| m:1+t:2 | 沪市A股 |
| m:1+t:23 | 科创板 |
| m:90+t:2 | 行业板块 |
| m:90+t:3 | 概念板块 |
| m:90+t:1 | 地域板块 |

**实测样例 URL**（BOARD_DAILY，你之前贴的那个）：
```
https://push2.eastmoney.com/api/qt/clist/get
  ?pn=1&pz=200&po=1&np=1&fltt=2&invt=2&fid=f3
  &fs=m:90+t:2,m:90+t:3,m:90+t:1
  &fields=f12,f14,f3,f62,f104,f105
```

**注意**：你之前贴的那个带 `f1,f2,f3...f173` 全量 fields 的 URL，fs 是 `m:0+t:6,m:0+t:80,m:1+t:2,m:1+t:23`（个股列表），那是 **MAIN_FUND_STOCK / 个股资金流** 的 URL，不是 stock_daily。stock_daily 走的是 push2his kline 接口。

---

### 2.3 push2ex.eastmoney.com（ZT_POOL — 涨跌停池）

**baseUrl**：`https://push2ex.eastmoney.com/{path}`

**URL 模板**：
```
https://push2ex.eastmoney.com/{path}
  ?ut=7eea3edcaed734bea9cbfc24409ed989
  &d={YYYYMMDD}
  &Pageindex={页码}
  &pagesize=200
```

**按 taskType 路由**（path 是路由关键）：

| taskType | path | 入库表 | type 列值 |
|---|---|---|---|
| LIMIT_POOL（涨停） | getTopicZTPool | limit_pool | limit_up |
| LIMIT_POOL（跌停） | getTopicDTPool | limit_pool | limit_down |
| LIMIT_POOL（炸板） | getTopicZBPool | limit_pool | zhaban |
| STRONG_POOL | getTopicQSPool | limit_pool | strong |
| CIXIN_POOL | getTopicCXPooll | limit_pool | cixin |

**注意**：LIMIT_POOL 在种子阶段拆成 limit_up / limit_down / zhaban 三个子任务（SeedGenerator），各自独立 URL。

**实测可用**（不需要代理）：
```
https://push2ex.eastmoney.com/getTopicZTPool?date=20260802  → 涨停池 ✅
https://push2ex.eastmoney.com/getTopicDTPool?date=20260802  → 跌停池 ✅
https://push2ex.eastmoney.com/getTopicZBPool?date=20260802  → 炸板池 ✅
https://push2ex.eastmoney.com/getTopicQSPool?date=20260802  → 强势池 ✅
https://push2ex.eastmoney.com/getTopicCXPooll?date=20260802 → 次新池 ✅
```

---

### 2.4 datacenter-web.eastmoney.com（DATACENTER — 龙虎榜）

**baseUrl**：`https://datacenter-web.eastmoney.com/api/data/get`

**URL 模板**：
```
https://datacenter-web.eastmoney.com/api/data/get
  ?type={RPT_DAILYBILLBOARD_DETAILS|RPT_BILLBOARD_DETAIL}
  &filter=(TRADE_DATE%3D%27{YYYY-MM-DD}%27)(SECURITY_CODE%3D%27{code}%27)
  &page_size=1000&pz=1000&po=1
  &fields1=f1&fields2=f2,f3,f4,f5,f6,f7
```

**按 taskType 路由**：

| taskType | type 参数 | 入库表 |
|---|---|---|
| DRAGON_TIGER | RPT_DAILYBILLBOARD_DETAILS | dragon_tiger |
| DRAGON_TIGER_DETAIL | RPT_BILLBOARD_DETAIL | dt_detail |

---

## 三、f 码 → schema 列名 速查（CLIST 解析用）

完整映射见 `EastmoneyFieldMap.java`，高频使用的：

| f 码 | schema 列 | 含义 |
|---|---|---|
| f12 | ts_code / board_code | 代码（无后缀） |
| f13 | __market | 市场码（0深/1沪，内部用） |
| f14 | stock_name / board_name | 名称 |
| f3 | pct_chg | 涨跌幅% |
| f4 | chg_amount | 涨跌额 |
| f5 | vol | 成交量(手) |
| f6 | amount | 成交额(元) |
| f7 | amplitude | 振幅% |
| f8 | turnover | 换手率% |
| f9 | pe | 市盈率(TTM) |
| f10 | volume_ratio | 量比 |
| f11 | avg_price | 均价 |
| f62 | main_net | 主力净流入 |
| f66 | super_big | 超大单净流入 |
| f72 | big_net | 大单净流入 |
| f78 | mid_net | 中单净流入 |
| f84 | small_net | 小单净流入 |
| f104 | up_count | 板块上涨家数 |
| f105 | down_count | 板块下跌家数 |
| f115 | pe_static | 静态市盈率 |
| f128 | leader_code | 领涨股代码 |
| f140 | board_code | 所属行业代码 |
| f141 | board_code2 | 所属概念代码 |
| f152 | market_code | 市场码（0深/1沪/2京） |

**ts_code 后缀补全**：`toTsCode(f12, f13)` — f13=1 → .SH, f13=0 → .SZ

---

## 四、DATACENTER 大写列名 → schema 列名

| datacenter 列名 | schema 列 | 含义 |
|---|---|---|
| SECUCODE | ts_code | 带后缀代码（000009.SZ） |
| SECURITY_NAME_ABBR | stock_name | 名称 |
| EXPLAIN | reason | 上榜原因 |
| EXPLANATION | explanation | 上榜原因(详) |
| BILLBOARD_BUY_AMT | total_buy | 买入金额 |
| BILLBOARD_SELL_AMT | total_sell | 卖出金额 |
| BILLBOARD_NET_AMT | net_buy | 净买额 |
| BILLBOARD_DEAL_AMT | billboard_deal_amt | 龙虎榜成交额 |
| MARKET | market | 市场(SZ/BJ/SH) |
| CLOSE_PRICE | close_price | 收盘价 |
| CHANGE_RATE | change_rate | 涨跌幅 |
| TURNOVERRATE | turnoverrate | 换手率 |
| FREE_MARKET_CAP | free_market_cap | 流通市值 |
| BUY_SEAT / SELL_SEAT | buy_seat / sell_seat | 席位数 |

---

## 五、URL → 表 速查卡片（速查用）

```
URL 包含                            → 入库表
─────────────────────────────────────────────────────
push2his.eastmoney.com + kline       → stock_daily / stock_weekly / index_daily
push2.eastmoney.com + fs=m:90        → board_daily（行业/概念/地域板块）
push2.eastmoney.com + fs=m:0/m:1     → main_fund_flow（个股/板块资金流）
push2ex.eastmoney.com + ZTPool       → limit_pool（涨停）
push2ex.eastmoney.com + DTPool       → limit_pool（跌停）
push2ex.eastmoney.com + ZBPool       → limit_pool（炸板）
push2ex.eastmoney.com + QSPool       → limit_pool（强势）
push2ex.eastmoney.com + CXPooll      → limit_pool（次新）
datacenter-web.eastmoney.com         → dragon_tiger / dt_detail
```

---

## 六、AKShare 接口 → 入库表 映射（补充）

> AKShare 部分接口走的是与上面相同的东财 endpoint，部分走新浪/同花顺源。

| AKShare 接口 | 底层数据源 | 入库表 | 需代理 |
|---|---|---|---|
| stock_zh_a_hist | push2his（同 #1） | stock_daily / stock_weekly | ✅ |
| stock_zh_a_hist(weekly) | push2his（同 #2） | stock_weekly | ✅ |
| stock_zh_index_daily | **新浪** | index_daily | ❌ |
| stock_board_industry_hist_em | push2.eastmoney.com | board_daily | ✅ |
| stock_board_concept_hist_em | push2.eastmoney.com | board_daily | ✅ |
| stock_individual_fund_flow | push2.eastmoney.com | main_fund_flow | ✅ |
| stock_lhb_detail_em | datacenter-web | dragon_tiger | ✅ |
| stock_zt_pool_em | push2ex（同 #9） | limit_pool | ❌ |
| stock_zt_pool_dtgc_em | push2ex | limit_pool | ❌ |
| stock_zt_pool_zbgc_em | push2ex | limit_pool | ❌ |
| stock_zt_pool_strong_em | push2ex | limit_pool | ❌ |
| stock_zt_pool_sub_new_em | push2ex | limit_pool | ❌ |
| stock_hsgt_fund_flow_summary_em | 东财 | northbound_flow | ❌ |
| stock_board_industry_summary_ths | **同花顺** | board_daily(涨跌家数) | ❌ |
| stock_zh_a_spot_em | push2.eastmoney.com | stock_universe | ✅ |
