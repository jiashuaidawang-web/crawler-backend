# 同花顺 hexin-v 签名逆向报告

> 研究对象：同花顺（10jqka.com.cn）反爬签名 `hexin-v`
> 逆向对象：`https://s.thsi.cn/js/chameleon/chameleon.1.13.min.js`（63KB，混淆）
> 日期：2026-08-02

## 1. hexin-v 的真实身份

`hexin-v.js` 不是独立文件，而是同花顺反爬系统 **chameleon（变色龙）**。
页面以 `<script src="//s.thsi.cn/js/chameleon/chameleon.1.13.min.js">` 引入。
混淆方式：字符串数组 + switch 分派 + 145 个 `c["XxxYy"]` 成员。

## 2. 完整生命周期

```
页面加载 chameleon.min.js
  │
  ├─ Init() 分派（window.CHAMELEON_LOADED 守卫，仅一次）
  │   ├─ s.Init() / b.Init()    ── 指纹采集器就绪
  │   ├─ initCDPDetection()     ── 检测 DevTools / ChromeDriver / domAutomation
  │   ├─ initAsyncEncryptionSeed() ── Promise 链算加密种子
  │   │     种子 o = (cdp<<2 | canvas2D<<1 | webGL) , 再与时间低 8 位混合
  │   ├─ initCanvasDetection()  ── Canvas / WebGL 指纹
  │   └─ (devtools 检测 Init：threshold 300ms)
  │
  ├─ r() 首次生成 token
  │     r = new T([4,4,4,4,1,1,1,3,2,2,2,2,2,2,2,4,2,1,4,4,1])   // 21 字段指纹向量
  │     r[1]=serverTimeNow     r[2]=now
  │     r[3]=strhash(UA)       r[4]=getPlatform()
  │     r[5]=getBrowserIndex() r[6]=getPluginNum()
  │     r[7]=getMouseMove()    ← 鼠标移动计数 u（mousemove/touchmove 累加）
  │     r[8]=getMouseClick()   ← 鼠标点击计数 s（校验 isTrusted）
  │     r[9]=getMouseWhell()   ← 滚轮计数 a
  │     r[10]=getKeyDown()     ← 键盘计数 f
  │     r[11]/[12]=clickPos    r[13]=browserFeature  r[14]=pathLengthRatio
  │     r[15]=getComprehensiveAutomationFeatures()
  │           └─ 探测 webdriver / $cdc_ / domAutomation / __selenium_evaluate / __nightmare / HeadlessChrome
  │     r[18]=inputIntervalVariance  r[19]=clickDurationVariance  r[20]=0
  │     token = m.encode( r.toBuffer() )
  │
  └─ token 输出三通道
      ① document.cookie "v_c=<token>; path=/"
      ② window.CHAMELEON_CALLBACK(token)      ← 页面/我们注入的钩子
      ③ 后续请求自动附加（XHR/fetch 拦截）：
           URL 白名单外 → ?hexin-v=<encodeURIComponent(token)>
           或 Header: X-Antispider-Message: <token>

服务端往返：
  响应头 X-Antispider-Message → w.analysisRst(w.parse(header)) 更新 token
  status_code 1001 → top.location.href = header.redirect_url（拦截/跳转验证页）
  status_code 1002 → 同上
```

## 3. 字符串表关键映射

```
f="hexin-v"                       token 名
c["WMIQw"]="CHAMELEON_CALLBACK"   入口回调名
c["VWnog"]="X-Antispider-Message" 响应头名
c["xLnTA"]=function(a,b){return a+b}   字符串拼接
c["asPgv"]=function(a,b){return a-b}   减法
TOKEN_SERVER_TIME=1785606536.794  服务端时间戳（每个下发版本不同）
swOHp="v_c="   csLYj="; path=/"
exemption v=[/s.thsi.cn/,/so.thsi.cn/,/myhuaweicloud.com/]  不附 token 的域名
```

## 4. 为什么纯 Node 无头 mock 不可行

| 字段 | 含义 | 纯 Node 里的值 | 服务端怎么看 |
|------|------|----------------|--------------|
| r[7] | mouseMove | 0（无鼠标事件） | 无交互 |
| r[8] | mouseClick | 0 | 无交互 |
| r[9] | wheel | 0 | 无交互 |
| r[10] | keyDown | 0 | 无交互 |
| r[15] | automationFlags | 全亮（webdriver/$cdc_ 等被探测到） | 这是机器人 |

token 不是静态哈希，而是**实时行为 + 自动化检测的编码**。无交互无头环境生成的 token，
服务端 `analysisRst` 必然返回 1001/1002 触发跳转拦截。

实测：Node mock 环境能跑通混淆 JS，但 `callbackToken=null`（不产出 token）；
真实 headless Chromium 在 `about:blank` 也不产出（依赖真实 `location.hostname`）。

## 5. 可行形态：真实浏览器内运行 chameleon

见 `extractor.js` / `server.js`：
- Playwright headless Chromium 加载真实同花顺页面
- `addInitScript` 注入 stealth（覆盖 navigator.webdriver 等）
- 在 chameleon 之前劫持 `CHAMELEON_CALLBACK`
- 模拟真实交互（贝塞尔折线鼠标移动 + 点击 + 滚轮 + 键盘）
- 从回调 / `v` cookie / 请求参数三通道提取 token

## 5.1 实测验证（2026-08-02，端到端打通）

**token 落点修正**：chameleon 把 token 写到 cookie **`v`**（不是 `v_c`），域 `.10jqka.com.cn`。
提取逻辑以读取 cookie `v` 为准。

**闭环验证**（代理 `45.38.107.97:6014`，目标 `data.10jqka.com.cn`）：

```
1. 提取 token
   data.10jqka.com.cn/market/zdfph/ → chameleon loaded: true
   cookie v = A6F_CQJGbx7lusPqP61fJn9esGa-ThVtP8C5VgN1nagHas-cS54lEM8SySGQ

2. 带 token 调接口
   GET /market/zdfph/field/order/desc/page/1/ajax/1/?hexin-v=<token>
   → 56662 字节，真实数据页「涨跌风排_同花顺财经」✅

3. 对照：不带 token
   GET /market/zdfph/field/order/desc/page/1/ajax/1/
   → 501 字节，服务端返回反爬 JS（chameleon.min.1785613.js）→ 拦截 ✅
```

**结论："方法A"全流程打通** —— 分析 chameleon → 真实浏览器运行 → 模拟交互 → 提取 hexin-v → 调用接口拿到数据。
token 有效窗口内可直接用于同花顺 `data.10jqka.com.cn` 的数据接口。

## 6. 一个值得注意的观察

`stockpage.10jqka.com.cn` 是 Next.js SPA，**不加载 chameleon**，
访问返回 200 且无拦截（仅需常规统计 cookie HMACCOUNT/Hm_lvt）。
chameleon 只加载在传统页面（如 `quote.10jqka.com.cn/center/`）。
这意味着：若数据可从 stockpage 的前端接口拿到，可能根本不需要 hexin-v；
需要 hexin-v 的是传统接口（quote/data 等）——具体取决于目标接口域名。

## 7. 文件清单

```
hexin-v-extractor/
├── chameleon.1.13.min.js   逆向对象（原始混淆 JS）
├── extractor.js            核心提取器（HexinVExtractor 类）
├── server.js               HTTP 服务（POST /extract → token）
├── cli.js                  CLI 自测
├── package.json
├── JavaClient.java         Java 调用示例（设计说明）
└── REVERSE_REPORT.md      本文件
```

## 8. 运行

```bash
cd hexin-v-extractor
npm install   # 拉 playwright（浏览器复用本机已缓存的 chromium-1228）

# 服务模式
node server.js 9090
curl -X POST http://localhost:9090/extract -H 'content-type: application/json' \
  -d '{"url":"https://quote.10jqka.com.cn/center/","proxy":"http://u:p@h:p"}'

# CLI 自测
node cli.js https://quote.10jqka.com.cn/center/ http://u:p@h:p
```

前提：需 `CHROMIUM_PATH` 指向可用 Chromium，以及能到同花顺的住宅代理。

## 9. 代理配置与实测坑（2026-08-02 Bright Data 验证）

**已验证可用的代理**：Bright Data `web_unlocker1` 住宅区域

```
http://brd-customer-hl_ba4ae03b-zone-web_unlocker1:3krfm2jkaltf@brd.superproxy.io:44445
```

**实测质量**：延迟 4-12 秒/请求，可靠性 ~70-80%（首次请求常失败，偶发连接重置）。能过 chameleon，但不算高质量；建议升级到 Bright Data 的 `isp`（静态住宅）区域。

**三个坑（已内置到 extractor.js）**：

1. **Chromium 必须强制 HTTP/1.1**（`--disable-http2 --disable-quic`）。Bright Data 住宅 peer 节点对 Chromium HTTP/2 多路复用支持不稳 → 随机 `ERR_CONNECTION_RESET`。curl 默认 HTTP/1.1 所以 curl 通但 Chromium 断——这是最难排查的差异。
2. **必须忽略 HTTPS 错误**（`ignoreHTTPSErrors:true`）。Bright Data 超级代理的 SSL 中间证书不被 Chromium 信任。
3. **导航用 commit + 长超时（60s）**，不等全部子资源（`i.thsi.cn` 图片、`cbasspider.10jqka.com.cn:8443` 上报接口会失败，但不影响主文档和 token）。

**代理认证注意**：Playwright 的 `proxy.server` 不解析 URL userinfo 作代理认证，必须显式拆 username/password（`extractor._parseProxy` 已处理，否则 407/连接重置——与 crawler-strategy 的 BrowserContextFactory.parseProxy 同理）。
