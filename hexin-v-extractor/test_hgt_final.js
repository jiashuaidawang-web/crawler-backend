/**
 * 测试北向资金 API - 基于 diffRequest 模式
 * URL: /{baseUrl}/{key1}/{value1}/{key2}/{value2}/.../ajax/1/
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

    // 基于 diffRequest 模式构造 URL
    // URL: /{baseUrl}/{key1}/{value1}/{key2}/{value2}/.../ajax/1/
    const hgtApis = [
        // 北向资金 - board/getHgtPage
        `https://data.10jqka.com.cn/hgt/hgtb/board/getHgtPage/ajax/1/`,
        `https://data.10jqka.com.cn/hgt/hgtb/board/getHgtPage/page/1/ajax/1/`,
        `https://data.10jqka.com.cn/hgt/hgtb/board/getHgtPage/page/1/field/zdf/order/desc/ajax/1/`,
        // 港股通
        `https://data.10jqka.com.cn/hgt/hgtb/board/getGgtPage/ajax/1/`,
        `https://data.10jqka.com.cn/hgt/hgtb/board/getSgtPage/ajax/1/`,
        // 尝试不同的 baseUrl
        `https://data.10jqka.com.cn/hgt/hgtb/board/getHgtPage/ajax/1/free/1/`,
        `https://data.10jqka.com.cn/hgt/hgtb/board/getGgtPage/ajax/1/free/1/`,
        `https://data.10jqka.com.cn/hgt/hgtb/board/getSgtPage/ajax/1/free/1/`,
        // 不带 board
        `https://data.10jqka.com.cn/hgt/hgtb/getHgtPage/ajax/1/`,
        `https://data.10jqka.com.cn/hgt/hgtb/getHgtPage/page/1/ajax/1/`,
        // 尝试 code 参数
        `https://data.10jqka.com.cn/hgt/hgtb/op/code/code//ajax/1/`,
        // 更多组合
        `https://data.10jqka.com.cn/hgt/hgtb/board/getHgtPage/page/1/ajax/1/free/1/`,
        `https://data.10jqka.com.cn/hgt/hgtb/board/getGgtPage/page/1/ajax/1/free/1/`,
        `https://data.10jqka.com.cn/hgt/hgtb/board/getSgtPage/page/1/ajax/1/free/1/`,
    ];

    for (const url of hgtApis) {
        try {
            const resp = await fetch(url, H("https://data.10jqka.com.cn/hgt/hgtb/"));
            if (resp.status === 200 && resp.text.length > 100) {
                console.log(`\n[OK] ${url}`);
                console.log(`  Length: ${resp.text.length}`);
                const rows = parseHtmlTable(resp.text);
                if (rows.length > 0) {
                    console.log(`  Rows: ${rows.length}`);
                    console.log(`  Header: ${JSON.stringify(rows[0])}`);
                    for (let i = 1; i < Math.min(rows.length, 4); i++) {
                        console.log(`  Row${i}: ${JSON.stringify(rows[i])}`);
                    }
                } else {
                    console.log(`  Text: ${resp.text.substring(0, 400)}`);
                }
            } else if (resp.status !== 404) {
                console.log(`  [${resp.status}] ${url} (${resp.text.length})`);
                if (resp.text.length > 0 && resp.text.length < 200) console.log(`    ${resp.text}`);
            }
        } catch (e) {
            // ignore
        }
        await new Promise(r => setTimeout(r, 200));
    }

    // 也尝试 POST
    console.log(`\n\n=== 尝试 POST ===`);
    const post = async (url, postData) => {
        return new Promise((resolve, reject) => {
            const urlObj = new URL(url);
            const options = {
                hostname: urlObj.hostname,
                port: urlObj.port || 443,
                path: urlObj.pathname + urlObj.search,
                method: 'POST',
                headers: {
                    ...H("https://data.10jqka.com.cn/hgt/hgtb/"),
                    'Content-Type': 'application/x-www-form-urlencoded',
                    'Content-Length': Buffer.byteLength(postData),
                },
            };
            const req = https.request(options, (res) => {
                const chunks = [];
                res.on('data', chunk => chunks.push(chunk));
                res.on('end', () => {
                    let buffer = Buffer.concat(chunks);
                    if (res.headers['content-encoding'] === 'gzip') {
                        try { buffer = zlib.gunzipSync(buffer); } catch(e) {}
                    }
                    let text;
                    try { text = iconv.decode(buffer, 'gbk'); } catch { text = buffer.toString('utf-8'); }
                    resolve({ status: res.statusCode, text });
                });
            });
            req.on('error', reject);
            req.setTimeout(15000, () => { req.destroy(); reject(new Error('timeout')); });
            req.write(postData);
            req.end();
        });
    };

    const postTests = [
        { url: "https://data.10jqka.com.cn/hgt/hgtb/ajax/1/", params: "board=getHgtPage&ajax=1" },
        { url: "https://data.10jqka.com.cn/hgt/hgtb/ajax/1/", params: "board=getHgtPage&ajax=1&page=1" },
        { url: "https://data.10jqka.com.cn/hgt/hgtb/ajax/1/", params: "board=getGgtPage&ajax=1" },
        { url: "https://data.10jqka.com.cn/hgt/hgtb/ajax/1/", params: "board=getSgtPage&ajax=1" },
        { url: "https://data.10jqka.com.cn/hgt/hgtb/board/getHgtPage/ajax/1/", params: "page=1&ajax=1" },
    ];

    for (const test of postTests) {
        try {
            const resp = await post(test.url, test.params);
            if (resp.status === 200 && resp.text.length > 100) {
                console.log(`\n[OK] POST ${test.url}`);
                console.log(`  Params: ${test.params}`);
                console.log(`  Length: ${resp.text.length}`);
                const rows = parseHtmlTable(resp.text);
                if (rows.length > 0) {
                    console.log(`  Rows: ${rows.length}`);
                    console.log(`  Header: ${JSON.stringify(rows[0])}`);
                    for (let i = 1; i < Math.min(rows.length, 4); i++) {
                        console.log(`  Row${i}: ${JSON.stringify(rows[i])}`);
                    }
                } else {
                    console.log(`  Text: ${resp.text.substring(0, 400)}`);
                }
            } else if (resp.status !== 404) {
                console.log(`  [${resp.status}] POST ${test.url} params=${test.params} (${resp.text.length})`);
            }
        } catch (e) {
            // ignore
        }
        await new Promise(r => setTimeout(r, 200));
    }
}

main().catch(console.error);
