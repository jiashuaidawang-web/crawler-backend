# 同花顺资金流向 API 文档

## 概述
本文档记录同花顺（10jqka）资金流向相关数据的 API 接口。所有 API 需要 `hexin-v` token（通过 chameleon JS 获取）。

## Token 获取
- JS 文件: `chameleon.1.13.min.js`
- 方法: 使用 jsdom 加载 JS，通过 `CHAMELEON_CALLBACK` 回调或 cookie 中的 `v` 字段获取
- Token 有效期较短，每个请求前需要刷新

## 通用请求头
```
Accept: text/html, */*; q=0.01
Accept-Encoding: gzip, deflate
Accept-Language: zh-CN,zh;q=0.9
Host: data.10jqka.com.cn
hexin-v: <token>
Referer: <页面URL>
User-Agent: Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36
X-Requested-With: XMLHttpRequest
```

## API 列表

### Q1-行业资金流
- **URL**: `http://data.10jqka.com.cn/funds/hyzjl/field/tradezdf/order/desc/ajax/1/free/1/`
- **Referer**: `http://data.10jqka.com.cn/funds/hyzjl/`
- **每页**: 51 行
- **总页数**: 2 页
- **字段**: 序号, 行业, 行业指数, 涨跌幅, 流入资金(亿), 流出资金(亿), 净额(亿), 公司家数, 领涨股, 涨跌幅, 当前价(元)
- **URL 模式**: `/funds/hyzjl/field/{字段}/order/{方向}/ajax/1/free/1/`
  - 可换字段: `tradezdf` (涨跌幅), `jlr` (净额) 等
  - 可换方向: `desc`, `asc`

### Q1-概念资金流
- **URL**: `http://data.10jqka.com.cn/funds/gnzjl/field/tradezdf/order/desc/ajax/1/free/1/`
- **Referer**: `http://data.10jqka.com.cn/funds/gnzjl/`
- **每页**: 51 行
- **总页数**: 8 页
- **字段**: 序号, 行业, 行业指数, 涨跌幅, 流入资金(亿), 流出资金(亿), 净额(亿), 公司家数, 领涨股, 涨跌幅, 当前价(元)

### Q2-个股资金流
- **URL**: `http://data.10jqka.com.cn/funds/ggzjl/field/zdf/order/desc/ajax/1/free/1/`
- **Referer**: `http://data.10jqka.com.cn/funds/ggzjl/`
- **每页**: 51 行
- **总页数**: 105 页
- **字段**: 序号, 股票代码, 股票简称, 最新价, 涨跌幅, 换手率, 流入资金(元), 流出资金(元), 净额(元), 成交额(元), 大单流入(元)
- **URL 模式**: `/funds/ggzjl/field/{字段}/order/{方向}/ajax/1/free/1/`
- **3日汇总**: `/funds/ggzjl/board/3/field/zdf/order/desc/ajax/1/free/1/`

### Q3-龙虎榜
- **URL**: `https://data.10jqka.com.cn/ifmarket/lhbtable/stock/all/ajax/1/`
- **Referer**: `https://data.10jqka.com.cn/market/longhu/`
- **行数**: 61 行
- **字段**: (标记), 代码, 名称, 现价, 涨跌幅, 成交金额, 净买入额
- **排序**: `/ifmarket/lhbtable/stock/all/field/zdf/order/desc/page/1/ajax/1/`

### Q3-龙虎榜营业部
- **URL**: `https://data.10jqka.com.cn/ifmarket/lhbyyb/report/{YYYY-MM-DD}/ajax/1/`
- **Referer**: `https://data.10jqka.com.cn/market/longhu/yyb/`
- **行数**: 21 行
- **字段**: 序号, 营业部名称, 上榜次数, 合计动用资金, 年内上榜次数, 年内买入股票只数, 年内3日跟买成功率

### Q4-北向资金
- **URL**: `https://data.10jqka.com.cn/hgt/hgtb/board/getHgtPage/ajax/1/`
- **Referer**: `https://data.10jqka.com.cn/hgt/hgtb/`
- **行数**: 11 行（最近交易日）
- **字段**: 日期, 当日资金流入（元）, 当日余额（元）, 当日成交净买额（元）, 买入成交额（元）, 卖出成交额（元）, 领涨股, 领涨股涨跌幅, 上证指数, 涨跌幅

## 数据格式
所有 API 返回 HTML 表格数据（GBK 编码），需要解析 `<table>` 中的 `<tr>` 和 `<td>` 标签。

## 注意事项
1. Token 需要每次请求前刷新
2. 请求频率不宜过高，避免被封
3. 北向资金 API 使用 `diffRequest` 模式构造 URL: `/{baseUrl}/{key1}/{value1}/{key2}/{value2}/.../ajax/1/`
4. 龙虎榜营业部 URL 需要日期参数
5. 行业/概念/个股资金流支持分页，总页数在页面的 `class="page_info"` 中
