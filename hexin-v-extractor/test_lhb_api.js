/**
 * 找龙虎榜的正确 API 路径
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

    // 尝试多种龙虎榜 URL
    const lhbApis = [
        "https://data.10jqka.com.cn/market/lhb/",
        "https://data.10jqka.com.cn/market/lhb/cxg/",
        "https://data.10jqka.com.cn/market/lhb/statistics/",
        "https://data.10jqka.com.cn/market/lhb/seat/",
        "https://data.10jqka.com.cn/market/lhb/cxg/board/1/",
        "https://data.10jqka.com.cn/market/lhb/cxg/ajax/1/",
        "https://data.10jqka.com.cn/market/lhb/api/list/",
        "https://data.10jqka.com.cn/market/lhb/api/detail/",
        "https://data.10jqka.com.cn/funds/lhb/",
        "https://data.10jqka.com.cn/funds/lhb/cxg/",
        "https://data.10jqka.com.cn/funds/lhb/statistics/",
        "https://data.10jqka.com.cn/market/lhb/type/1/",
        "https://data.10jqka.com.cn/market/lhb/type/2/",
        "https://data.10jqka.com.cn/market/lhb/type/3/",
        "https://data.10jqka.com.cn/market/lhb/type/4/",
        "https://data.10jqka.com.cn/market/lhb/type/5/",
    ];

    for (const url of lhbApis) {
        try {
            const resp = await fetch(url, H("https://data.10jqka.com.cn/market/lhb/"));
            if (resp.status === 200 && resp.text.length > 200) {
                console.log(`\n[OK] ${url}`);
                console.log(`  Status: ${resp.status}, Length: ${resp.text.length}`);
                const rows = parseHtmlTable(resp.text);
                if (rows.length > 0) {
                    console.log(`  Rows: ${rows.length}`);
                    console.log(`  Header: ${JSON.stringify(rows[0])}`);
                    if (rows.length > 1) console.log(`  Row1: ${JSON.stringify(rows[1])}`);
                } else {
                    console.log(`  Text: ${resp.text.substring(0, 300)}`);
                }
            } else {
                console.log(`  [${resp.status}] ${url} (${resp.text.length})`);
            }
        } catch (e) {
            console.log(`  [ERR] ${url}: ${e.message}`);
        }
        await new Promise(r => setTimeout(r, 200));
    }

    // 北向资金
    console.log(`\n${'='*60}`);
    console.log("北向资金 API 路径");
    console.log('='*60);

    const northApis = [
        "https://data.10jqka.com.cn/hsgt/",
        "https://data.10jqka.com.cn/hsgt/api/",
        "https://data.10jqka.com.cn/hsgt/api/zjlx/",
        "https://data.10jqka.com.cn/hsgt/api/zjlx/boardType/5/",
        "https://data.10jqka.com.cn/hsgt/api/zjlx/boardType/6/",
        "https://data.10jqka.com.cn/hsgt/api/zjlx/boardType/8/",
        "https://data.10jqka.com.cn/hsgt/index/",
        "https://data.10jqka.com.cn/funds/hsgt/",
        "https://data.10jqka.com.cn/funds/hsgt/api/",
        "https://data.10jqka.com.cn/hsgt/zjlx/",
    ];

    for (const url of northApis) {
        try {
            const resp = await fetch(url, H("https://data.10jqka.com.cn/hsgt/"));
            if (resp.status === 200 && resp.text.length > 200) {
                console.log(`\n[OK] ${url}`);
                console.log(`  Status: ${resp.status}, Length: ${resp.text.length}`);
                const rows = parseHtmlTable(resp.text);
                if (rows.length > 0) {
                    console.log(`  Rows: ${rows.length}`);
                    console.log(`  Header: ${JSON.stringify(rows[0])}`);
                    if (rows.length > 1) console.log(`  Row1: ${JSON.stringify(rows[1])}`);
                } else {
                    console.log(`  Text: ${resp.text.substring(0, 300)}`);
                }
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
