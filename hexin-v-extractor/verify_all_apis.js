/**
 * 同花顺资金流向 API 完整验证
 * 验证所有 6 个维度的数据 API
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

    const apis = [
        {
            name: "Q1-行业资金流",
            url: "http://data.10jqka.com.cn/funds/hyzjl/field/tradezdf/order/desc/ajax/1/free/1/",
            ref: "http://data.10jqka.com.cn/funds/hyzjl/",
        },
        {
            name: "Q1-概念资金流",
            url: "http://data.10jqka.com.cn/funds/gnzjl/field/tradezdf/order/desc/ajax/1/free/1/",
            ref: "http://data.10jqka.com.cn/funds/gnzjl/",
        },
        {
            name: "Q2-个股资金流",
            url: "http://data.10jqka.com.cn/funds/ggzjl/field/zdf/order/desc/ajax/1/free/1/",
            ref: "http://data.10jqka.com.cn/funds/ggzjl/",
        },
        {
            name: "Q3-龙虎榜",
            url: "https://data.10jqka.com.cn/ifmarket/lhbtable/stock/all/ajax/1/",
            ref: "https://data.10jqka.com.cn/market/longhu/",
        },
        {
            name: "Q3-龙虎榜营业部",
            url: "https://data.10jqka.com.cn/ifmarket/lhbyyb/report/20260822/ajax/1/",
            ref: "https://data.10jqka.com.cn/market/longhu/yyb/",
        },
        {
            name: "Q4-北向资金",
            url: "https://data.10jqka.com.cn/hgt/hgtb/board/getHgtPage/ajax/1/",
            ref: "https://data.10jqka.com.cn/hgt/hgtb/",
        },
    ];

    const results = [];
    for (const api of apis) {
        console.log(`\n${"=".repeat(70)}`);
        console.log(`[${api.name}]`);
        console.log(`URL: ${api.url}`);
        try {
            const resp = await fetch(api.url, H(api.ref));
            if (resp.status === 200 && resp.text.length > 100) {
                const rows = parseHtmlTable(resp.text);
                console.log(`Status: ${resp.status}, Length: ${resp.text.length}`);
                console.log(`Rows: ${rows.length}`);
                if (rows.length > 0) {
                    console.log(`Header: ${JSON.stringify(rows[0])}`);
                    for (let i = 1; i < Math.min(rows.length, 4); i++) {
                        console.log(`Row${i}: ${JSON.stringify(rows[i])}`);
                    }
                }
                results.push({ name: api.name, status: 'OK', rows: rows.length, header: rows[0] || [] });
            } else {
                console.log(`Status: ${resp.status}, Length: ${resp.text.length}`);
                results.push({ name: api.name, status: `FAIL(${resp.status})`, rows: 0, header: [] });
            }
        } catch (e) {
            console.log(`Error: ${e.message}`);
            results.push({ name: api.name, status: `ERR(${e.message})`, rows: 0, header: [] });
        }
        await new Promise(r => setTimeout(r, 300));
    }

    // 汇总
    console.log(`\n\n${"=".repeat(70)}`);
    console.log("汇总");
    console.log("=".repeat(70));
    for (const r of results) {
        const status = r.status === 'OK' ? '✅' : '❌';
        console.log(`${status} ${r.name}: ${r.status} (${r.rows} rows)`);
        if (r.header.length > 0) {
            console.log(`   字段: ${JSON.stringify(r.header)}`);
        }
    }
}

main().catch(console.error);
