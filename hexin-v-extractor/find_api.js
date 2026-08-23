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
                resolve({ status: res.statusCode, text });
            });
        });
        req.on('error', reject);
        req.setTimeout(15000, () => { req.destroy(); reject(new Error('timeout')); });
    });
}

async function main() {
    const token = await getToken();

    // 获取北向资金页面源码
    const resp = await fetch("https://data.10jqka.com.cn/hsgt/", {
        "Accept": "text/html,application/xhtml+xml",
        "Accept-Encoding": "gzip, deflate",
        "Accept-Language": "zh-CN,zh;q=0.9",
        "Host": "data.10jqka.com.cn",
        "hexin-v": token,
        "Referer": "https://data.10jqka.com.cn/",
        "User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36",
    });

    console.log("Status:", resp.status, "Length:", resp.text.length);

    // 找所有 script 标签内容
    const scriptMatches = resp.text.match(/<script[^>]*>([\s\S]*?)<\/script>/g) || [];
    console.log(`\nFound ${scriptMatches.length} script tags`);

    // 找包含 ajax/fetch/url 的 script
    for (const script of scriptMatches) {
        if (script.includes('ajax') || script.includes('url') || script.includes('fetch') || script.includes('api')) {
            console.log(`\n--- Script with URL references ---`);
            // 提取所有 URL
            const urls = script.match(/["']\/[^"']+["']/g) || [];
            console.log("URLs found:", urls.slice(0, 20));
            // 提取 ajax 调用
            const ajaxCalls = script.match(/\$\.(ajax|get|getJSON|post)\([^)]*\)/g) || [];
            console.log("Ajax calls:", ajaxCalls.slice(0, 10));
        }
    }

    // 找所有 href/src 中的 API 路径
    const allUrls = resp.text.match(/["']https?:\/\/[^"']+["']/g) || [];
    console.log(`\nAll external URLs (${allUrls.length}):`);
    allUrls.slice(0, 30).forEach(u => console.log("  ", u));

    // 找 data 属性中的 URL
    const dataUrls = resp.text.match(/data-[a-z]+=["'][^"']+["']/g) || [];
    console.log(`\nData attributes (${dataUrls.length}):`);
    dataUrls.slice(0, 20).forEach(u => console.log("  ", u));
}

main().catch(console.error);
