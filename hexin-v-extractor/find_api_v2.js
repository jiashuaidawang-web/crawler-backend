/**
 * 从同花顺网站 JS 源码中找龙虎榜和北向资金的 AJAX API 路径
 */
const { JSDOM } = require('jsdom');
const http = require('http');
const https = require('https');
const zlib = require('zlib');
const fs = require('fs');
const path = require('path');
const iconv = require('iconv-lite');

function getToken() {
    return new Promise((resolve, reject) => {
        const jsCode = fs.readFileSync(path.join(__dirname, 'chameleon.1.13.min.js'), 'utf-8');
        const dom = new JSDOM('<!DOCTYPE html><html><body></body></html>', {
            url: 'https://data.10jqka.com.cn/',
            pretendToBeVisual: true,
            runScripts: 'dangerously',
        });
        const { window } = dom;
        let capturedToken = null;
        window.CHAMELEON_CALLBACK = (token) => { capturedToken = token; };
        window.eval(jsCode);
        setTimeout(() => {
            if (capturedToken) return resolve(capturedToken);
            const cookies = window.document.cookie;
            const vMatch = cookies.match(/v=([^;]+)/);
            if (vMatch) return resolve(vMatch[1]);
            reject(new Error('no token'));
        }, 2000);
    });
}

function fetch(url, headers) {
    return new Promise((resolve, reject) => {
        const mod = url.startsWith('https') ? https : http;
        const req = mod.get(url, { headers }, (res) => {
            const chunks = [];
            res.on('data', chunk => chunks.push(chunk));
            res.on('end', () => {
                let buffer = Buffer.concat(chunks);
                const encoding = res.headers['content-encoding'];
                if (encoding === 'gzip') {
                    try { buffer = zlib.gunzipSync(buffer); } catch(e) {}
                }
                let text;
                try { text = iconv.decode(buffer, 'gbk'); } catch { text = buffer.toString('utf-8'); }
                resolve({ status: res.statusCode, text, headers: res.headers });
            });
        });
        req.on('error', reject);
        req.setTimeout(15000, () => { req.destroy(); reject(new Error('timeout')); });
    });
}

async function main() {
    const token = await getToken();
    console.log(`Token: ${token}\n`);

    const H = (referer) => ({
        "Accept": "text/html,application/xhtml+xml",
        "Accept-Encoding": "gzip, deflate",
        "Accept-Language": "zh-CN,zh;q=0.9",
        "Host": "data.10jqka.com.cn",
        "hexin-v": token,
        "Referer": referer,
        "User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36",
    });

    // 1. 获取北向资金页面，找所有 JS 文件
    console.log("=== 北向资金页面分析 ===");
    const hsgtResp = await fetch("https://data.10jqka.com.cn/hsgt/", H("https://data.10jqka.com.cn/"));
    console.log(`Status: ${hsgtResp.status}, Length: ${hsgtResp.text.length}`);

    // 找所有 script src
    const scriptSrcs = hsgtResp.text.match(/<script[^>]*src=["']([^"']+)["']/gi) || [];
    console.log(`\nFound ${scriptSrcs.length} script tags:`);
    scriptSrcs.forEach(s => console.log(`  ${s}`));

    // 找所有 JS 文件 URL
    const jsUrls = [];
    scriptSrcs.forEach(s => {
        const m = s.match(/src=["']([^"']+)["']/);
        if (m) {
            let url = m[1];
            if (url.startsWith('//')) url = 'https:' + url;
            else if (url.startsWith('/')) url = 'https://data.10jqka.com.cn' + url;
            else if (!url.startsWith('http')) url = 'https://data.10jqka.com.cn/' + url;
            jsUrls.push(url);
        }
    });

    // 2. 分析每个 JS 文件，找 API 路径
    console.log(`\n=== 分析 ${jsUrls.length} 个 JS 文件 ===`);
    for (const jsUrl of jsUrls) {
        console.log(`\n[JS] ${jsUrl}`);
        try {
            const jsResp = await fetch(jsUrl, H("https://data.10jqka.com.cn/hsgt/"));
            if (jsResp.status === 200 && jsResp.text.length > 50) {
                const jsText = jsResp.text;

                // 找所有 URL 路径
                const urlMatches = jsText.match(/["']\/[^"']+["']/g) || [];
                const apiUrls = urlMatches.filter(u =>
                    u.includes('ajax') || u.includes('api') || u.includes('data') ||
                    u.includes('json') || u.includes('hsgt') || u.includes('lhb') ||
                    u.includes('funds') || u.includes('zjlx')
                );
                if (apiUrls.length > 0) {
                    console.log(`  API URLs found: ${apiUrls.length}`);
                    apiUrls.slice(0, 20).forEach(u => console.log(`    ${u}`));
                }

                // 找 ajax 调用
                const ajaxMatches = jsText.match(/\$\.(ajax|get|getJSON|post)\s*\([^)]*\)/gi) || [];
                if (ajaxMatches.length > 0) {
                    console.log(`  Ajax calls: ${ajaxMatches.length}`);
                    ajaxMatches.slice(0, 10).forEach(m => console.log(`    ${m.substring(0, 150)}`));
                }

                // 找 url: 模式
                const urlPatternMatches = jsText.match(/url\s*:\s*["'][^"']+["']/gi) || [];
                if (urlPatternMatches.length > 0) {
                    console.log(`  url: patterns: ${urlPatternMatches.length}`);
                    urlPatternMatches.slice(0, 15).forEach(m => console.log(`    ${m}`));
                }
            }
        } catch (e) {
            console.log(`  Error: ${e.message}`);
        }
        await new Promise(r => setTimeout(r, 300));
    }

    // 3. 龙虎榜页面分析
    console.log(`\n\n=== 龙虎榜页面分析 ===`);
    const lhbResp = await fetch("https://data.10jqka.com.cn/market/lhb/", H("https://data.10jqka.com.cn/"));
    console.log(`Status: ${lhbResp.status}, Length: ${lhbResp.text.length}`);

    const lhbScriptSrcs = lhbResp.text.match(/<script[^>]*src=["']([^"']+)["']/gi) || [];
    console.log(`\nFound ${lhbScriptSrcs.length} script tags:`);
    lhbScriptSrcs.forEach(s => console.log(`  ${s}`));

    // 4. 尝试基于已知模式构造 URL
    console.log(`\n\n=== 尝试构造 API URL ===`);

    const tryUrls = [
        // 北向资金
        "https://data.10jqka.com.cn/hsgt/field/zdf/order/desc/ajax/1/free/1/",
        "https://data.10jqka.com.cn/hsgt/board/5/field/zdf/order/desc/ajax/1/free/1/",
        "https://data.10jqka.com.cn/hsgt/board/6/field/zdf/order/desc/ajax/1/free/1/",
        "https://data.10jqka.com.cn/hsgt/board/8/field/zdf/order/desc/ajax/1/free/1/",
        "https://data.10jqka.com.cn/hsgt/field/zdf/order/desc/page/1/ajax/1/free/1/",
        "https://data.10jqka.com.cn/funds/hsgt/field/zdf/order/desc/ajax/1/free/1/",
        "https://data.10jqka.com.cn/funds/hsgt/field/tradezdf/order/desc/ajax/1/free/1/",
        // 龙虎榜
        "https://data.10jqka.com.cn/market/lhb/field/zdf/order/desc/ajax/1/free/1/",
        "https://data.10jqka.com.cn/market/lhb/cxg/field/zdf/order/desc/ajax/1/free/1/",
        "https://data.10jqka.com.cn/market/lhb/statistics/field/zdf/order/desc/ajax/1/free/1/",
        "https://data.10jqka.com.cn/funds/lhb/field/zdf/order/desc/ajax/1/free/1/",
        "https://data.10jqka.com.cn/funds/lhb/cxg/field/zdf/order/desc/ajax/1/free/1/",
    ];

    const token2 = await getToken();
    for (const url of tryUrls) {
        try {
            const resp = await fetch(url, {
                "Accept": "text/html, */*; q=0.01",
                "Accept-Encoding": "gzip, deflate",
                "Accept-Language": "zh-CN,zh;q=0.9",
                "Host": "data.10jqka.com.cn",
                "hexin-v": token2,
                "Referer": "https://data.10jqka.com.cn/",
                "User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36",
                "X-Requested-With": "XMLHttpRequest",
            });
            if (resp.status === 200 && resp.text.length > 200) {
                console.log(`\n[OK] ${url}`);
                console.log(`  Length: ${resp.text.length}`);
                console.log(`  Preview: ${resp.text.substring(0, 300)}`);
            } else {
                console.log(`  [${resp.status}] ${url} (${resp.text.length})`);
            }
        } catch (e) {
            console.log(`  [ERR] ${url}: ${e.message}`);
        }
        await new Promise(r => setTimeout(r, 200));
    }
}

main().catch(console.error);
