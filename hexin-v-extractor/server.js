// hexin-v-extractor/server.js
// HTTP 服务：Java worker 调 POST /extract 拿 hexin-v token。
//
// 启动:  node server.js [port]      (默认 9090)
// 调用:  curl -X POST http://localhost:9090/extract \
//          -H 'content-type: application/json' \
//          -d '{"url":"https://data.10jqka.com.cn/market/zdfph/","proxy":"http://user:pass@host:port"}'
// 返回:  { ok, token, v, cookies, hexinInRequests, ms }
//
// GET /health -> { ok:true, chromium: <path> }
//
// 已验证代理（2026-08-02）：Bright Data web_unlocker1 住宅
//   proxy = http://brd-customer-hl_ba4ae03b-zone-web_unlocker1:3krfm2jkaltf@brd.superproxy.io:44445
//   注意：Chrominal 必须 --disable-http2 + ignoreHTTPSErrors（extractor 已内置）
//   质量：延迟 4-12s，可靠性 ~70%，需 60s 超时 + 重试换 IP

const http = require('http');
const { HexinVExtractor, CHROMIUM_DEFAULT } = require('./extractor');

const PORT = parseInt(process.argv[2] || process.env.PORT || '9090', 10);

// 单例浏览器实例复用（token 提取是长上下文；进程内保一个 extractor）。
const extractor = new HexinVExtractor({});

function send(res, code, obj) {
  const body = JSON.stringify(obj, null, 2);
  res.writeHead(code, {
    'content-type': 'application/json; charset=utf-8',
    'content-length': Buffer.byteLength(body),
  });
  res.end(body);
}

const server = http.createServer(async (req, res) => {
  if (req.method === 'GET' && req.url === '/health') {
    return send(res, 200, { ok: true, chromium: CHROMIUM_DEFAULT, time: Date.now() });
  }
  if (req.method === 'POST' && req.url === '/extract') {
    let raw = '';
    for await (const chunk of req) raw += chunk;
    let body = {};
    try { body = raw ? JSON.parse(raw) : {}; } catch (e) { return send(res, 400, { ok: false, error: 'bad json' }); }
    const { url, proxy, interact, settleMs } = body;
    if (!url) return send(res, 400, { ok: false, error: 'url required' });
    const t0 = Date.now();
    try {
      // 若调用方带了代理，临时换用（否则用构造时的默认代理）
      if (proxy && proxy !== extractor.proxy) {
        await extractor.close();
        extractor.proxy = proxy;
      }
      const r = await extractor.extract(url, { interact, settleMs });
      return send(res, r.ok ? 200 : 502, { ...r, ms: Date.now() - t0 });
    } catch (e) {
      return send(res, 502, { ok: false, error: e.message, ms: Date.now() - t0 });
    }
  }
  send(res, 404, { ok: false, error: 'not found', use: 'POST /extract or GET /health' });
});

server.listen(PORT, () => {
  console.log(`[hexin-v-extractor] listening on http://0.0.0.0:${PORT}`);
  console.log(`[hexin-v-extractor] chromium: ${CHROMIUM_DEFAULT}`);
});

process.on('SIGINT', async () => { await extractor.close(); process.exit(0); });
process.on('SIGTERM', async () => { await extractor.close(); process.exit(0); });
