# akshare-bridge

`crawler-backend` 的 **财报（financial）数据源桥接服务**。

## 为什么需要它

东财 `datacenter` 报告接口当前统一返回 `code=9501`（"返回字段参数不能为空"），
`emweb` F10 又有反爬重定向，均无法直接抓取财报。因此 `financial` 表改用
[akshare](https://github.com/akfamily/akshare) 作为数据源——它已封装好新浪/东财财报页面解析，
稳定返回 营收 / 净利润 / 同比 / ROE。

`crawler-admin` 的 `FinancialSeeder` 通过 HTTP 调用本服务，逐只股票取财报后写入 ClickHouse。

## 部署

与 `crawler-admin` **同机部署即可**（只要该机能访问东财/新浪，无需额外代理）：

```bash
cd akshare-bridge
pip install -r requirements.txt
uvicorn akshare_bridge:app --host 0.0.0.0 --port 8800
# 或自定义端口：PORT=8801 uvicorn akshare_bridge:app --host 0.0.0.0 --port 8800
```

健康检查：

```bash
curl http://localhost:8800/healthz
# {"ok":true,"akshare":true}
```

## 接口

### POST /financial
请求体：
```json
{ "ts_code": "000001", "start_year": "2018" }
```
返回（已自动补交易所后缀，如 `000001` → `000001.SZ`）：
```json
{
  "ts_code": "000001.SZ",
  "rows": [
    {
      "ts_code": "000001.SZ",
      "end_date": "2026-03-31",
      "report_type": "Q1",
      "ann_date": null,
      "revenue": 35277000000.0,
      "net_profit": 14523000000.0,
      "net_profit_yoy": 9.98,
      "roe": 3.27
    }
  ]
}
```

### GET /healthz
返回 `{"ok": true, "akshare": true}`。

## crawler 侧开关

在 `crawler-admin/src/main/resources/application.yml`：

```yaml
akshare:
  bridge:
    enabled: false          # 改为 true 启用
    url: http://localhost:8800
    max-stocks: 200         # 单次 /seed-financial 最多覆盖股票数
```

启用并部署本服务后，触发：

```bash
curl -X POST http://<admin>:8081/api/crawl/seed-financial \
  -H 'Content-Type: application/json' \
  -d '{"source":0,"tsCodes":["000001","600000"],"limit":null}'
# tsCodes 可空：空则从 stock_daily 最新交易日去重取股票池，封顶 max-stocks
```

## 数据映射（来自 akshare `stock_financial_abstract`）

| financial 列     | akshare 指标名            |
|------------------|---------------------------|
| revenue          | 营业总收入                 |
| net_profit       | 归母净利润                 |
| net_profit_yoy   | 归属母公司净利润增长率     |
| roe              | 净资产收益率(ROE)         |

`end_date` 由报告期列（如 `20260331`）规整为 `YYYY-MM-DD`；`report_type` 由月末推断
（03-31→Q1 / 06-30→Q2 / 09-30→Q3 / 12-31→年报）。`ann_date` 该接口不含，留 null。
