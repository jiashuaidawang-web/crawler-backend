/**
 * 同花顺资金流向 API 验证 v3
 * - 正确处理 GBK 编码
 * - 找北向资金和龙虎榜的正确 API
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
                // 尝试 GBK 解码
                let text;
                try {
                    text = iconv.decode(buffer, 'gbk');
                } catch {
                    text = buffer.toString('utf-8');
                }
                resolve({ status: res.statusCode, text });
            });
        });
        req.on('error', reject);
        req.setTimeout(15000, () => { req.destroy(); reject(new Error('timeout')); });
    });
}

function parseHtmlTable(html) {
    const rowRegex = /<tr[^>]*>([\s\S]*?)<\/tr>/g;
    const cellRegex = /<t[dh][^>]*>([\s\S]*?)<\/t[dh]>/g;
    const tagRegex = /<[^>]+>/g;
    const rows = [];
    let match;
    while ((match = rowRegex.exec(html)) !== null) {
        const rowHtml = match[1];
        const cells = [];
        let cellMatch;
        while ((cellMatch = cellRegex.exec(rowHtml)) !== null) {
            cells.push(cellMatch[1].replace(tagRegex, '').trim());
        }
        if (cells.length > 0) rows.push(cells);
        cellRegex.lastIndex = 0;
    }
    return rows;
}

async function main() {
    const token = await getToken();
    console.log(`Token: ${token}\n`);

    const H = (referer) => ({
        "Accept": "text/html, */*; q=0.01",
        "Accept-Encoding": "gzip, deflate",
        "Accept-Language": "zh-CN,zh;q=0.9",
        "Host": "data.10jqka.com.cn",
        "hexin-v": token,
        "Referer": referer,
        "User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36",
        "X-Requested-With": "XMLHttpRequest",
    });

    // 主要 API 测试
    const apis = [
        { name: "行业资金-p1", url: "http://data.10jqka.com.cn/funds/hyzjl/field/tradezdf/order/desc/ajax/1/free/1/", ref: "http://data.10jqka.com.cn/funds/hyzjl/" },
        { name: "概念资金-p1", url: "http://data.10jqka.com.cn/funds/gnzjl/field/tradezdf/order/desc/ajax/1/free/1/", ref: "http://data.10jqka.com.cn/funds/gnzjl/" },
        { name: "个股资金-p1", url: "http://data.10jqka.com.cn/funds/ggzjl/field/zdf/order/desc/ajax/1/free/1/", ref: "http://data.10jqka.com.cn/funds/ggzjl/" },
    ];

    for (const api of apis) {
        console.log(`\n${'='*60}`);
        console.log(`[${api.name}]`);
        try {
            const resp = await fetch(api.url, H(api.ref));
            console.log(`Status: ${resp.status}, Length: ${resp.text.length}`);
            if (resp.status === 200) {
                const rows = parseHtmlTable(resp.text);
                console.log(`Table rows: ${rows.length}`);
                if (rows.length > 0) {
                    console.log(`Header: ${JSON.stringify(rows[0], null, 0)}`);
                    for (let i = 1; i < Math.min(rows.length, 5); i++) {
                        console.log(`Row ${i}: ${JSON.stringify(rows[i])}`);
                    }
                }
                const pageMatch = resp.text.match(/class="page_info"[^>]*>1\/(\d+)/);
                if (pageMatch) console.log(`Total pages: ${pageMatch[1]}`);
            }
        } catch (e) {
            console.log(`Error: ${e.message}`);
        }
        await new Promise(r => setTimeout(r, 500));
    }

    // 找北向资金和龙虎榜的正确 API - 从页面源码中找 XHR 请求
    console.log(`\n${'='*60}`);
    console.log("从页面源码中找北向资金和龙虎榜的 API");
    console.log('='*60);

    // 尝试直接访问北向资金页面，从中找到 AJAX 请求
    const pages = [
        { name: "北向资金页面", url: "https://data.10jqka.com.cn/hsgt/", ref: "https://data.10jqka.com.cn/" },
        { name: "龙虎榜页面", url: "https://data.10jqka.com.cn/market/lhb/", ref: "https://data.10jqka.com.cn/" },
    ];

    for (const p of pages) {
        console.log(`\n[${p.name}] ${p.url}`);
        try {
            const resp = await fetch(p.url, {
                "Accept": "text/html,application/xhtml+xml",
                "Accept-Encoding": "gzip, deflate",
                "Accept-Language": "zh-CN,zh;q=0.9",
                "Host": "data.10jqka.com.cn",
                "hexin-v": token,
                "Referer": p.ref,
                "User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36",
            });
            console.log(`Status: ${resp.status}, Length: ${resp.text.length}`);
            if (resp.status === 200) {
                // 找所有 AJAX URL
                const ajaxMatches = resp.text.match(/url\s*[:=]\s*['"][^'"]+['"]/g) || [];
                console.log(`Found ${ajaxMatches.length} URL patterns`);
                ajaxMatches.slice(0, 10).forEach(m => console.log(`  ${m}`));

                // 找 data.10jqka.com.cn 的 API 路径
                const apiMatches = resp.text.match(/["']https?:\/\/data\.10jqka\.com\.cn\/[^"']+["']/g) || [];
                console.log(`Found ${apiMatches.length} API paths`);
                apiMatches.slice(0, 15).forEach(m => console.log(`  ${m}`));

                // 找 fetch/ajax 调用
                const fetchMatches = resp.text.match(/\$\.(ajax|get|getJSON)\([^)]+\)/g) || [];
                console.log(`Found ${fetchMatches.length} ajax calls`);
                fetchMatches.slice(0, 10).forEach(m => console.log(`  ${m.substring(0, 120)}`));
            }
        } catch (e) {
            console.log(`Error: ${e.message}`);
        }
        await new Promise(r => setTimeout(r, 500));
    }
}

main().catch(console.error);
