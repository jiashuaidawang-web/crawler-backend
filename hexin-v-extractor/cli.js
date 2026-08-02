// hexin-v-extractor/cli.js
// CLI 自测：node cli.js <url> [proxy]
// 例：node cli.js https://data.10jqka.com.cn/market/zdfph/ http://user:pass@host:port
//
// 默认 forceHttp1:true（Bright Data 等住宅代理必须 HTTP/1.1，否则连接重置）
const { HexinVExtractor } = require('./extractor');

const url = process.argv[2] || 'https://data.10jqka.com.cn/market/zdfph/';
const proxy = process.argv[3] || null;

(async () => {
  const ex = new HexinVExtractor({ headless: true, proxy, forceHttp1: true });
  console.log(`[cli] extracting hexin-v for ${url}  proxy=${proxy || '(none)'}  forceHttp1=true`);
  try {
    const r = await ex.extract(url, { interact: true, settleMs: 2000 });
    console.log(JSON.stringify(r, null, 2));
    if (!r.ok) process.exitCode = 1;
  } catch (e) {
    console.error('[cli] FAILED:', e.message);
    process.exitCode = 1;
  } finally {
    await ex.close();
  }
})();
