/**
 * 分析龙虎榜和北向资金页面的 JS 源码，找 AJAX API
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

function extractJsUrls(html, baseUrl) {
    const scriptSrcs = html.match(/<script[^>]*src=["']([^"']+)["']/gi) || [];
    const jsUrls = [];
    scriptSrcs.forEach(s => {
        const m = s.match(/src=["']([^"']+)["']/);
        if (m) {
            let url = m[1];
            if (url.startsWith('//')) url = 'https:' + url;
            else if (url.startsWith('/')) url = new URL(url, baseUrl).href;
            else if (!url.startsWith('http')) url = new URL(url, baseUrl).href;
            jsUrls.push(url);
        }
    });
    return jsUrls;
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

    const pages = [
        { name: "龙虎榜", url: "https://data.10jqka.com.cn/market/longhu/" },
        { name: "龙虎榜-营业部", url: "https://data.10jqka.com.cn/market/longhu/yyb/" },
        { name: "龙虎榜-机构", url: "https://data.10jqka.com.cn/market/longhu/jgzy/" },
        { name: "北向资金", url: "https://data.10jqka.com.cn/hgt/hgtb/" },
    ];

    for (const page of pages) {
        console.log(`\n${"=".repeat(70)}`);
        console.log(`[${page.name}] ${page.url}`);
        console.log("=".repeat(70));

        const resp = await fetch(page.url, H("https://data.10jqka.com.cn/"));
        console.log(`Status: ${resp.status}, Length: ${resp.text.length}`);

        if (resp.status !== 200) continue;

        // 提取 JS 文件 URL
        const jsUrls = extractJsUrls(resp.text, page.url);
        console.log(`\nJS files (${jsUrls.length}):`);
        jsUrls.forEach(u => console.log(`  ${u}`));

        // 分析每个 JS 文件
        for (const jsUrl of jsUrls) {
            console.log(`\n--- Analyzing: ${jsUrl} ---`);
            try {
                const jsResp = await fetch(jsUrl, H(page.url));
                if (jsResp.status === 200 && jsResp.text.length > 50) {
                    const jsText = jsResp.text;

                    // 找 URL 模式
                    const urlPatterns = jsText.match(/["']\/[^"']+["']/g) || [];
                    const apiCandidates = urlPatterns.filter(u =>
                        u.includes('ajax') || u.includes('api') || u.includes('json') ||
                        u.includes('longhu') || u.includes('hgt') || u.includes('hsgt') ||
                        u.includes('funds') || u.includes('zjlx') || u.includes('data')
                    );
                    if (apiCandidates.length > 0) {
                        console.log(`  API URL candidates (${apiCandidates.length}):`);
                        [...new Set(apiCandidates)].slice(0, 30).forEach(u => console.log(`    ${u}`));
                    }

                    // 找 url: "..." 或 url: '...'
                    const urlColons = jsText.match(/url\s*:\s*["'][^"']+["']/gi) || [];
                    if (urlColons.length > 0) {
                        console.log(`  url: patterns (${urlColons.length}):`);
                        [...new Set(urlColons)].slice(0, 20).forEach(m => console.log(`    ${m}`));
                    }

                    // 找 axios.get/post
                    const axiosCalls = jsText.match(/axios\.(get|post|put|delete)\s*\([^)]*\)/gi) || [];
                    if (axiosCalls.length > 0) {
                        console.log(`  axios calls (${axiosCalls.length}):`);
                        [...new Set(axiosCalls)].slice(0, 20).forEach(m => console.log(`    ${m.substring(0, 200)}`));
                    }

                    // 找 $.ajax
                    const ajaxCalls = jsText.match(/\$\.(ajax|get|getJSON|post)\s*\([^)]*\)/gi) || [];
                    if (ajaxCalls.length > 0) {
                        console.log(`  jQuery ajax (${ajaxCalls.length}):`);
                        [...new Set(ajaxCalls)].slice(0, 20).forEach(m => console.log(`    ${m.substring(0, 200)}`));
                    }

                    // 找 fetch(
                    const fetchCalls = jsText.match(/fetch\s*\([^)]*\)/gi) || [];
                    if (fetchCalls.length > 0) {
                        console.log(`  fetch calls (${fetchCalls.length}):`);
                        [...new Set(fetchCalls)].slice(0, 20).forEach(m => console.log(`    ${m.substring(0, 200)}`));
                    }
                }
            } catch (e) {
                console.log(`  Error: ${e.message}`);
            }
            await new Promise(r => setTimeout(r, 300));
        }
    }
}

main().catch(console.error);
