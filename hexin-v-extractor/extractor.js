// hexin-v-extractor/extractor.js
// 同花顺 hexin-v 签名提取器（"方法A"的可行形态）。
//
// 核心事实（逆向 chameleon.1.13.min.js 得到）：
//   - hexin-v 由反爬系统 chameleon（变色龙）在浏览器端生成，不是纯静态哈希。
//   - token = encode(toBuffer(指纹向量 r[21]))，其中 r[7..10] 是实时事件计数器
//     (鼠标移动 u / 点击 s / 滚轮 a / 键盘 f)，r[15] 是自动化检测位
//     (webdriver/$cdc_/domAutomation/__selenium_evaluate/__nightmare)。
//   - 纯 Node 无头 mock 无法生成有效 token（行为计数全 0 + 自动化标志亮起 →
//     服务端 X-Antispider-Message 返回 1001/1002 拦截）。
//   - 因此本提取器在真实 headless Chromium 里跑 chameleon，模拟真实交互后提取 token。
//
// 用法：
//   const { HexinVExtractor } = require('./extractor');
//   const ex = new HexinVExtractor({ chromiumPath, proxy: 'http://...' });
//   const { token, cookies } = await ex.extract('https://quote.10jqka.com.cn/center/');

const { chromium } = require('playwright');

const CHROMIUM_DEFAULT =
  process.env.CHROMIUM_PATH ||
  '/Users/null/Library/Caches/ms-playwright/chromium-1228/chrome-mac-x64/' +
  'Google Chrome for Testing.app/Contents/MacOS/Google Chrome for Testing';

// 注入时机：在 chameleon 之前，捕获它回调出来的 token。
// chameleon 通过 window.CHAMELEON_CALLBACK(token) 把 token 交给页面，我们劫持它。
const CAPTURE_HOOK = `
  if (!window.__hexinCapture) {
    window.__hexinCapture = [];
    Object.defineProperty(window, 'CHAMELEON_CALLBACK', {
      configurable: true,
      set(fn) {
        window.__hexinCapture.push(fn);
        window.__realChameleonCb = fn;
      },
      get() { return window.__realChameleonCb; }
    });
    // 兜底：chameleon 也可能直接 window.CHAMELEON_CALLBACK(token) 调用
    const orig = window.CHAMELEON_CALLBACK;
    window.CHAMELEON_CALLBACK = function(t){ window.__hexinCapture.push(t); if(orig) orig(t); };
  }
`;

// chameleon 的自动化检测在 navigator/document 上读取；addInitScript 在每次导航前注入。
const STEALTH_INIT = `
  Object.defineProperty(navigator, 'webdriver', { get: () => undefined });
  Object.defineProperty(navigator, 'plugins', { get: () => [1,2,3,4,5] });
  Object.defineProperty(navigator, 'languages', { get: () => ['zh-CN','zh','en'] });
  Object.defineProperty(navigator, 'platform', { get: () => 'Win32' });
  try { window.chrome = window.chrome || { runtime:{}, loadTimes:function(){}, csi:function(){} }; } catch(e){}
`;

const UA =
  'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 ' +
  '(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36';

class HexinVExtractor {
  constructor(opts = {}) {
    this.chromiumPath = opts.chromiumPath || CHROMIUM_DEFAULT;
    this.proxy = opts.proxy || null;             // 'http://user:pass@host:port'
    this.headless = opts.headless !== false;
    this.forceHttp1 = opts.forceHttp1 === true;
    this.browser = null;
    this.ctx = null;
  }

  async launch() {
    if (this.ctx) return;
    const args = ['--no-sandbox', '--disable-setuid-sandbox', '--disable-blink-features=AutomationControlled'];
    // Bright Data 等住宅代理的 peer 节点对 Chromium HTTP/2 多路复用支持不稳，
    // 会随机 ERR_CONNECTION_RESET；强制 HTTP/1.1 后稳定（实测验证）。
    if (process.env.FORCE_HTTP1 === '1' || this.forceHttp1) {
      args.push('--disable-http2', '--disable-quic');
    }
    this.browser = await chromium.launch({
      headless: this.headless,
      executablePath: this.chromiumPath,
      args,
    });
    const contextOpts = {
      userAgent: UA,
      screen: { width: 1920, height: 1080 },
      viewport: { width: 1920, height: 1080 },
      locale: 'zh-CN',
      timezoneId: 'Asia/Shanghai',
      ignoreHTTPSErrors: true, // Bright Data 等代理的 SSL 链不被信任
    };
    if (this.proxy) {
      // Playwright 不会把 proxy URL 里的 userinfo 当作代理认证发送，必须拆出来，
      // 否则会收到 407/连接重置（与 BrowserContextFactory.parseProxy 同理）。
      contextOpts.proxy = this._parseProxy(this.proxy);
    }
    this.ctx = await this.browser.newContext(contextOpts);
    await this.ctx.addInitScript(STEALTH_INIT);
  }

  // 模拟真实用户交互，填充 chameleon 的行为计数器 (u/s/f/a)。
  // 路径用贝塞尔式折线，带随机步长与停顿，避免被 "isTrusted+路径线性" 启发式识破。
  async simulateInteraction(page) {
    const box = { w: 1920, h: 1080 };
    const rand = (a, b) => a + Math.random() * (b - a);
    // 鼠标移动：多段折线，steps 让轨迹连续
    const points = [
      [rand(200, 500), rand(200, 400)],
      [rand(600, 900), rand(300, 500)],
      [rand(400, 800), rand(500, 700)],
      [rand(300, 700), rand(200, 600)],
    ];
    for (const [x, y] of points) {
      await page.mouse.move(x, y, { steps: Math.floor(rand(6, 14)) });
      await page.waitForTimeout(rand(60, 180));
    }
    // 点击 (isTrusted 在真实浏览器中为 true，Playwright 派发的事件 isTrusted=true)
    await page.mouse.click(rand(400, 800), rand(300, 600));
    await page.waitForTimeout(rand(80, 200));
    // 滚轮
    for (let i = 0; i < 2; i++) {
      await page.mouse.wheel(0, Math.floor(rand(200, 500)));
      await page.waitForTimeout(rand(100, 250));
    }
    // 键盘
    for (let i = 0; i < 3; i++) {
      await page.keyboard.press(['Tab', 'ArrowDown', 'Enter', 'Space'][i % 4]);
      await page.waitForTimeout(rand(60, 160));
    }
    // 再移动一段，模拟阅读
    await page.mouse.move(rand(500, 900), rand(400, 650), { steps: 10 });
  }

  // 从浏览器提取 token：三通道取一。
  async _grabToken(page) {
    // 通道 1：CHAMELEON_CALLBACK 捕获
    const captured = await page.evaluate(() => window.__hexinCapture || []);
    for (const c of captured) {
      if (typeof c === 'string' && c.length > 8) return c;
    }
    // 通道 2：cookie。chameleon 把 token 写到 cookie "v"（setCookie("v",token,...)），
    // 域为 .10jqka.com.cn。实测确认：v 即 hexin-v token（base64 风格字符串），
    // 附到接口请求时用 ?hexin-v=<token> 即可拿到数据（无 token 则被服务端返回反爬 JS 拦截）。
    const cookies = await this.ctx.cookies();
    const vc = cookies.find(c => c.name === 'v');
    if (vc && vc.value) return vc.value;
    // 通道 3：页面内主动调用 update()（若 chameleon 暴露了 b.update）
    const fromUpdate = await page.evaluate(() => {
      try {
        // chameleon 把 analysisRst 挂到 window[l]，l='hexin-v' 名；尝试取 token
        return (window.__hexinV || null);
      } catch (e) { return null; }
    }).catch(() => null);
    return fromUpdate || null;
  }

  async extract(url, { interact = true, settleMs = 1500 } = {}) {
    await this.launch();
    const page = await this.ctx.newPage();
    // 在 chameleon 之前装捕获钩子
    await page.addInitScript(CAPTURE_HOOK);

    const tokenViaReq = [];
    page.on('request', (r) => {
      const u = r.url();
      const m = u.match(/[?&]hexin-v=([^&]+)/);
      if (m) tokenViaReq.push(decodeURIComponent(m[1]));
    });

    try {
      // 'commit' 不等子资源，避免高延迟代理下 domcontentloaded 超时；
      // 子资源（i.thsi.cn 图片、cbasspider 上报）失败不影响主文档与 token。
      await page.goto(url, { waitUntil: 'commit', timeout: 60000 });
      await page.waitForLoadState('domcontentloaded', { timeout: 60000 }).catch(() => {});
    } catch (e) {
      throw new Error(`navigate failed: ${e.message}`);
    }

    // 等 chameleon 执行加密种子链（Promise 链含 setTimeout，需等它落地）
    await page.waitForTimeout(400);

    if (interact) {
      await this.simulateInteraction(page);
    }

    // 等 token 写入 cookie
    await page.waitForTimeout(settleMs);

    let token = await this._grabToken(page);
    // 通道 4：从请求参数拿
    if (!token && tokenViaReq.length) token = tokenViaReq[0];

    const allCookies = await this.ctx.cookies();
    const vCookie = allCookies.find(c => c.name === 'v') || null;
    await page.close();

    return {
      token,
      url,
      cookies: allCookies.map(c => ({ name: c.name, value: c.value, domain: c.domain })),
      v: vCookie,
      hexinInRequests: tokenViaReq,
      ok: !!token,
    };
  }

  async close() {
    if (this.browser) {
      await this.browser.close();
      this.browser = null;
      this.ctx = null;
    }
  }

  _parseProxy(proxy) {
    if (!proxy) return { server: proxy };
    try {
      const u = new URL(proxy);
      const server = u.protocol + '//' + u.host;
      if (u.username) {
        return {
          server,
          username: decodeURIComponent(u.username),
          password: decodeURIComponent(u.password || ''),
        };
      }
      return { server };
    } catch (e) {
      return { server: proxy };
    }
  }
}

module.exports = { HexinVExtractor, CHROMIUM_DEFAULT, UA };
