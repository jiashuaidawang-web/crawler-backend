/**
 * 深入分析北向资金页面源码 + 龙虎榜页面探索
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

    // 1. 获取北向资金页面完整源码
    console.log("=== 北向资金页面源码 ===");
    const hsgtResp = await fetch("https://data.10jqka.com.cn/hsgt/", H("https://data.10jqka.com.cn/"));
    console.log(`Status: ${hsgtResp.status}`);
    console.log(`Content:\n${hsgtResp.text}`);

    // 2. 尝试龙虎榜相关页面
    console.log(`\n\n=== 龙虎榜页面探索 ===`);
    const lhbUrls = [
        "https://data.10jqka.com.cn/market/lhb/",
        "https://data.10jqka.com.cn/market/lhb/cxg/",
        "https://data.10jqka.com.cn/market/lhb/statistics/",
        "https://data.10jqka.com.cn/market/lhb/seat/",
        "https://data.10jqka.com.cn/lhb/",
        "https://data.10jqka.com.cn/lhb/cxg/",
        "https://data.10jqka.com.cn/lhb/statistics/",
        "https://data.10jqka.com.cn/funds/lhb/",
        "https://data.10jqka.com.cn/funds/lhb/cxg/",
        "https://data.10jqka.com.cn/funds/lhb/statistics/",
        "https://data.10jqka.com.cn/market/dragon/",
        "https://data.10jqka.com.cn/market/dragon/cxg/",
    ];

    for (const url of lhbUrls) {
        try {
            const resp = await fetch(url, H("https://data.10jqka.com.cn/"));
            console.log(`\n[${resp.status}] ${url} (${resp.text.length})`);
            if (resp.status === 200 && resp.text.length > 100) {
                console.log(`  Content: ${resp.text.substring(0, 500)}`);
            }
        } catch (e) {
            console.log(`  [ERR] ${url}: ${e.message}`);
        }
        await new Promise(r => setTimeout(r, 200));
    }

    // 3. 尝试从同花顺主站找龙虎榜和北向资金的入口
    console.log(`\n\n=== 同花顺主站入口 ===`);
    const mainUrls = [
        "https://data.10jqka.com.cn/",
        "http://data.10jqka.com.cn/",
    ];

    for (const url of mainUrls) {
        try {
            const resp = await fetch(url, H("https://data.10jqka.com.cn/"));
            console.log(`\n[${resp.status}] ${url} (${resp.text.length})`);
            if (resp.status === 200) {
                // 找所有链接
                const links = resp.text.match(/href=["']([^"']+)["']/gi) || [];
                const lhbLinks = links.filter(l => l.includes('lhb') || l.includes('longhu') || l.includes('dragon') || l.includes('hsgt') || l.includes('beixiang') || l.includes('north'));
                console.log(`  All links (${links.length}):`);
                links.slice(0, 30).forEach(l => console.log(`    ${l}`));
            }
        } catch (e) {
            console.log(`  [ERR] ${url}: ${e.message}`);
        }
        await new Promise(r => setTimeout(r, 200));
    }
}

main().catch(console.error);
