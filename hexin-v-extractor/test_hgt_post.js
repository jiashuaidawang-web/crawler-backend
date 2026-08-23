/**
 * 北向资金 - 分析 POST 请求模式
 */
const { JSDOM } = require('jsdom');
const http = require('http');
const https = require('https');
const zlib = require('zlib');
const fs = require('fs');
const path = require('path');
const iconv = require('iconv-lite');
const { execSync } = require('child_process');

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

function post(url, postData, headers) {
    return new Promise((resolve, reject) => {
        const urlObj = new URL(url);
        const allHeaders = {
            ...headers,
            'Content-Type': 'application/x-www-form-urlencoded',
            'Content-Length': Buffer.byteLength(postData),
        };
        const options = {
            hostname: urlObj.hostname,
            port: urlObj.port || (urlObj.protocol === 'https:' ? 443 : 80),
            path: urlObj.pathname + urlObj.search,
            method: 'POST',
            headers: allHeaders,
        };
        const mod = urlObj.protocol === 'https:' ? https : http;
        const req = mod.request(options, (res) => {
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
        req.write(postData);
        req.end();
    });
}

function fetchWithCurl(url) {
    try {
        const tmpFile = path.join(__dirname, '_tmp_' + Date.now() + '.js');
        execSync(`curl -sL --max-time 10 -o "${tmpFile}" "${url}"`, { timeout: 15000 });
        const content = fs.readFileSync(tmpFile, 'utf-8');
        fs.unlinkSync(tmpFile);
        return content;
    } catch (e) {
        return null;
    }
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

    // 1. 深入分析 main_v3.js 中的 AJAX 调用
    console.log("=== 分析 main_v3.js AJAX 调用 ===");
    const mainJs = fetchWithCurl("https://s.thsi.cn/js/datacenter/rzrq/main_v3.js");
    if (mainJs) {
        // 找所有 $.ajax 调用的上下文
        const ajaxContexts = mainJs.match(/.{0,200}\$\.ajax\(.{0,500}/gi) || [];
        console.log(`Found ${ajaxContexts.length} ajax calls:`);
        ajaxContexts.forEach((c, i) => {
            console.log(`\n--- Ajax ${i+1} ---`);
            console.log(c);
        });
    }

    // 2. 尝试 POST 请求
    console.log(`\n\n=== 尝试 POST 请求 ===`);

    const postUrls = [
        "https://data.10jqka.com.cn/hgt/hgtb/",
        "https://data.10jqka.com.cn/hgt/hgtb/ajax/1/",
    ];

    const postParams = [
        "board=getHgtPage&ajax=1",
        "board=getHgtPage&ajax=1&page=1",
        "board=getHgtPage&ajax=1&page=1&field=zdf&order=desc",
        "tjid=hgtb&ajax=1",
        "tjid=hgtb&ajax=1&page=1",
        "tjid=hgtb&board=getHgtPage&ajax=1",
        "op=code&code=&ajax=1",
        "op=code&code=&ajax=1&page=1",
    ];

    for (const url of postUrls) {
        for (const params of postParams) {
            try {
                const resp = await post(url, params, H("https://data.10jqka.com.cn/hgt/hgtb/"));
                if (resp.status === 200 && resp.text.length > 100) {
                    console.log(`\n[OK] POST ${url}`);
                    console.log(`  Params: ${params}`);
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
                } else if (resp.status !== 404 && resp.text.length > 0) {
                    console.log(`  [${resp.status}] ${url} params=${params} (${resp.text.length})`);
                    if (resp.text.length < 200) console.log(`    ${resp.text}`);
                }
            } catch (e) {
                // ignore
            }
            await new Promise(r => setTimeout(r, 150));
        }
    }

    // 3. 也尝试 GET 带查询参数
    console.log(`\n\n=== 尝试 GET 带查询参数 ===`);
    const getApis = [
        `https://data.10jqka.com.cn/hgt/hgtb/?board=getHgtPage&ajax=1`,
        `https://data.10jqka.com.cn/hgt/hgtb/?board=getHgtPage&ajax=1&page=1`,
        `https://data.10jqka.com.cn/hgt/hgtb/?tjid=hgtb&ajax=1`,
        `https://data.10jqka.com.cn/hgt/hgtb/?tjid=hgtb&ajax=1&page=1`,
        `https://data.10jqka.com.cn/hgt/hgtb/ajax/1/?board=getHgtPage`,
        `https://data.10jqka.com.cn/hgt/hgtb/ajax/1/?board=getHgtPage&page=1`,
    ];

    const token2 = await getToken();
    for (const url of getApis) {
        try {
            const resp = await fetch(url, {
                ...H("https://data.10jqka.com.cn/hgt/hgtb/"),
                "hexin-v": token2,
            });
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
            } else if (resp.status !== 404 && resp.text.length > 0) {
                console.log(`  [${resp.status}] ${url} (${resp.text.length})`);
                if (resp.text.length < 200) console.log(`    ${resp.text}`);
            }
        } catch (e) {
            // ignore
        }
        await new Promise(r => setTimeout(r, 200));
    }
}

main().catch(console.error);
