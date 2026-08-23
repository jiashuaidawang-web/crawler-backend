/**
 * 分析北向资金页面 - 找嵌入的数据
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

    const resp = await fetch("https://data.10jqka.com.cn/hgt/hgtb/", {
        "Accept": "text/html,application/xhtml+xml",
        "Accept-Encoding": "gzip, deflate",
        "Accept-Language": "zh-CN,zh;q=0.9",
        "Host": "data.10jqka.com.cn",
        "hexin-v": token,
        "Referer": "https://data.10jqka.com.cn/",
        "User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36",
    });

    // 保存完整页面
    fs.writeFileSync(path.join(__dirname, '_hgt_page.html'), resp.text, 'utf-8');
    console.log(`Page saved: ${resp.text.length} chars`);

    // 找所有 table 元素
    const tableMatches = resp.text.match(/<table[\s\S]*?<\/table>/gi) || [];
    console.log(`\nFound ${tableMatches.length} tables`);
    tableMatches.forEach((t, i) => {
        console.log(`\n--- Table ${i+1} (${t.length} chars) ---`);
        console.log(t.substring(0, 500));
    });

    // 找所有 script 标签内容
    const scriptMatches = resp.text.match(/<script[^>]*>([\s\S]*?)<\/script>/gi) || [];
    console.log(`\n\nFound ${scriptMatches.length} scripts`);
    scriptMatches.forEach((s, i) => {
        const content = s.replace(/<script[^>]*>/i, '').replace(/<\/script>/i, '');
        if (content.trim().length > 10) {
            console.log(`\n--- Script ${i+1} (${content.length} chars) ---`);
            // 找包含 data/hgt/hsgt 的行
            const lines = content.split('\n');
            for (const line of lines) {
                if (line.includes('data') || line.includes('hgt') || line.includes('hsgt') ||
                    line.includes('table') || line.includes('board') || line.includes('zjlx') ||
                    line.includes('stock') || line.includes('ajax') || line.includes('json')) {
                    console.log(`  ${line.trim().substring(0, 200)}`);
                }
            }
        }
    });

    // 找所有 a 标签中的链接
    const aMatches = resp.text.match(/<a[^>]*href=["']([^"']+)["'][^>]*>/gi) || [];
    console.log(`\n\nFound ${aMatches.length} links`);
    const dataLinks = aMatches.filter(a => a.includes('hgt') || a.includes('hsgt') || a.includes('zjlx') || a.includes('funds'));
    console.log(`\nData-related links (${dataLinks.length}):`);
    dataLinks.forEach(l => console.log(`  ${l}`));

    // 找 div 中的数据
    const divMatches = resp.text.match(/<div[^>]*id=["']([^"']+)["'][^>]*>/gi) || [];
    console.log(`\n\nDiv IDs:`);
    divMatches.forEach(d => console.log(`  ${d}`));
}

main().catch(console.error);
