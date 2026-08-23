/**
 * 同花顺资金流向 API 验证 v2
 * - 正确处理 gzip
 * - 解析 HTML 表格数据
 * - 每个请求刷新 token
 */
const { JSDOM } = require('jsdom');
const http = require('http');
const https = require('https');
const zlib = require('zlib');
const fs = require('fs');
const path = require('path');

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
                } else if (encoding === 'deflate') {
                    try { buffer = zlib.inflateSync(buffer); } catch(e) {}
                }
                resolve({ status: res.statusCode, text: buffer.toString('utf-8') });
            });
        });
        req.on('error', reject);
        req.setTimeout(15000, () => { req.destroy(); reject(new Error('timeout')); });
    });
}

function parseHtmlTable(html) {
    // 提取表格行
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

    // 测试 API 列表
    const apis = [
        { name: "行业资金-p1", url: "http://data.10jqka.com.cn/funds/hyzjl/field/tradezdf/order/desc/ajax/1/free/1/", ref: "http://data.10jqka.com.cn/funds/hyzjl/" },
        { name: "概念资金-p1", url: "http://data.10jqka.com.cn/funds/gnzjl/field/tradezdf/order/desc/ajax/1/free/1/", ref: "http://data.10jqka.com.cn/funds/gnzjl/" },
        { name: "个股资金-p1", url: "http://data.10jqka.com.cn/funds/ggzjl/field/zdf/order/desc/ajax/1/free/1/", ref: "http://data.10jqka.com.cn/funds/ggzjl/" },
        { name: "个股资金-3日", url: "http://data.10jqka.com.cn/funds/ggzjl/board/3/field/zdf/order/desc/ajax/1/free/1/", ref: "http://data.10jqka.com.cn/funds/ggzjl/" },
        { name: "大单追踪", url: "http://data.10jqka.com.cn/funds/ddzz/order/desc/ajax/1/free/1/", ref: "http://data.10jqka.com.cn/funds/ddzz/" },
    ];

    for (const api of apis) {
        console.log(`\n${'='*60}`);
        console.log(`[${api.name}]`);
        console.log(`URL: ${api.url}`);
        try {
            const resp = await fetch(api.url, H(api.ref));
            console.log(`Status: ${resp.status}, Length: ${resp.text.length}`);

            if (resp.status === 200 && resp.text.length > 100) {
                if (resp.text.trim().startsWith('{') || resp.text.trim().startsWith('[')) {
                    try {
                        const data = JSON.parse(resp.text);
                        console.log(`JSON keys: ${typeof data === 'object' && !Array.isArray(data) ? Object.keys(data).join(',') : `array[${data.length}]`}`);
                        console.log(`Preview: ${JSON.stringify(resp).substring(0, 500)}`);
                    } catch {
                        console.log(`Text: ${resp.text.substring(0, 300)}`);
                    }
                } else {
                    // 解析 HTML 表格
                    const rows = parseHtmlTable(resp.text);
                    console.log(`Table rows: ${rows.length}`);
                    if (rows.length > 0) {
                        console.log(`Header: ${JSON.stringify(rows[0])}`);
                        for (let i = 1; i < Math.min(rows.length, 5); i++) {
                            console.log(`Row ${i}: ${JSON.stringify(rows[i])}`);
                        }
                    }
                    // 提取总页数
                    const pageMatch = resp.text.match(/class="page_info"[^>]*>1\/(\d+)/);
                    if (pageMatch) console.log(`Total pages: ${pageMatch[1]}`);
                }
            } else {
                console.log(`Response: ${resp.text.substring(0, 200)}`);
            }
        } catch (e) {
            console.log(`Error: ${e.message}`);
        }
        await new Promise(r => setTimeout(r, 500));
    }

    // 额外：找北向资金和龙虎榜的正确 API
    console.log(`\n${'='*60}`);
    console.log("寻找北向资金和龙虎榜的正确 API 路径");
    console.log('='*60);

    const extraApis = [
        { name: "北向-1", url: "https://data.10jqka.com.cn/hsgt//ajax/1/", ref: "https://data.10jqka.com.cn/hsgt/" },
        { name: "北向-2", url: "https://data.10jqka.com.cn/hsgt/api/zjlx/boardType/5/", ref: "https://data.10jqka.com.cn/hsgt/" },
        { name: "北向-3", url: "https://data.10jqka.com.cn/hsgt/api/zjlx/boardType/6/", ref: "https://data.10jqka.com.cn/hsgt/" },
        { name: "龙虎-1", url: "https://data.10jqka.com.cn/market/lhb/ajax/1/", ref: "https://data.10jqka.com.cn/market/lhb/" },
        { name: "龙虎-2", url: "https://data.10jqka.com.cn/market/lhb/cxg/board/1/", ref: "https://data.10jqka.com.cn/market/lhb/" },
        { name: "龙虎-3", url: "https://data.10jqka.com.cn/market/lhb/api/list/", ref: "https://data.10jqka.com.cn/market/lhb/" },
    ];

    // 刷新 token
    const token2 = await getToken();
    for (const api of extraApis) {
        console.log(`\n[${api.name}] ${api.url}`);
        try {
            const resp = await fetch(api.url, H(api.ref));
            console.log(`  Status: ${resp.status}, Length: ${resp.text.length}`);
            if (resp.status === 200 && resp.text.length > 50) {
                if (resp.text.trim().startsWith('{') || resp.text.trim().startsWith('[')) {
                    console.log(`  JSON: ${resp.text.substring(0, 300)}`);
                } else {
                    const rows = parseHtmlTable(resp.text);
                    console.log(`  Rows: ${rows.length}`);
                    if (rows.length > 0) console.log(`  First: ${JSON.stringify(rows[0])}`);
                }
            } else {
                console.log(`  ${resp.text.substring(0, 150)}`);
            }
        } catch (e) {
            console.log(`  Error: ${e.message}`);
        }
        await new Promise(r => setTimeout(r, 300));
    }
}

main().catch(console.error);
