/**
 * 测试龙虎榜 API
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

function fetchWithCurl(url) {
    try {
        const tmpFile = path.join(__dirname, '_tmp_' + Date.now() + '.html');
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

    // 1. 先获取龙虎榜页面的完整 lhb JS 来理解参数
    console.log("=== 分析 lhb.20240325.js 完整内容 ===");
    const lhbJs = fetchWithCurl("https://s.thsi.cn/website/datacenter/lhb/lhb.20240325.js");
    if (lhbJs) {
        // 找所有 URL 相关的代码
        const urlContexts = lhbJs.match(/.{0,100}(lhbtable|lhbyyb|lhbjgzy|ajaxUrl|Url).{0,100}/gi) || [];
        console.log("URL contexts:");
        urlContexts.forEach(c => console.log(`  ${c}`));

        // 找 tableAjax 初始化
        const tableAjaxInit = lhbJs.match(/.{0,200}tableAjax.{0,200}/gi) || [];
        console.log("\ntableAjax init:");
        tableAjaxInit.forEach(c => console.log(`  ${c}`));

        // 找 param 对象
        const paramContexts = lhbJs.match(/.{0,100}param.{0,100}/gi) || [];
        console.log("\nparam contexts:");
        paramContexts.forEach(c => console.log(`  ${c}`));
    }

    // 2. 测试龙虎榜 API
    console.log(`\n\n=== 测试龙虎榜 API ===`);

    const today = new Date();
    const dateStr = `${today.getFullYear()}-${String(today.getMonth()+1).padStart(2,'0')}-${String(today.getDate()).padStart(2,'0')}`;
    // 也尝试 YYYYMMDD 格式
    const dateStr2 = `${today.getFullYear()}${String(today.getMonth()+1).padStart(2,'0')}${String(today.getDate()).padStart(2,'0')}`;

    const lhbApis = [
        // 龙虎榜主表
        `https://data.10jqka.com.cn/ifmarket/lhbtable/stock/all/report/${dateStr}/tab/all/field/STOCKCODE/sort/asc/page/1/ajax/1/`,
        `https://data.10jqka.com.cn/ifmarket/lhbtable/stock/all/report/${dateStr2}/tab/all/field/STOCKCODE/sort/asc/page/1/ajax/1/`,
        `https://data.10jqka.com.cn/ifmarket/lhbtable/stock/all/report/${dateStr}/tab/all/field/STOCKCODE/sort/asc/ajax/1/`,
        `https://data.10jqka.com.cn/ifmarket/lhbtable/stock/all/report/${dateStr2}/tab/all/field/STOCKCODE/sort/asc/ajax/1/`,
        // 简化版本
        `https://data.10jqka.com.cn/ifmarket/lhbtable/stock/all/report/${dateStr}/ajax/1/`,
        `https://data.10jqka.com.cn/ifmarket/lhbtable/stock/all/report/${dateStr2}/ajax/1/`,
        // 更简化
        `https://data.10jqka.com.cn/ifmarket/lhbtable/report/${dateStr}/ajax/1/`,
        `https://data.10jqka.com.cn/ifmarket/lhbtable/report/${dateStr2}/ajax/1/`,
        `https://data.10jqka.com.cn/ifmarket/lhbtable/ajax/1/`,
        // 营业部
        `https://data.10jqka.com.cn/ifmarket/lhbyyb/report/${dateStr}/ajax/1/`,
        `https://data.10jqka.com.cn/ifmarket/lhbyyb/report/${dateStr2}/ajax/1/`,
        // 不带日期
        `https://data.10jqka.com.cn/ifmarket/lhbtable/stock/all/ajax/1/`,
        `https://data.10jqka.com.cn/ifmarket/lhbtable/ajax/1/free/1/`,
    ];

    for (const url of lhbApis) {
        try {
            const resp = await fetch(url, H("https://data.10jqka.com.cn/market/longhu/"));
            if (resp.status === 200 && resp.text.length > 200) {
                console.log(`\n[OK] ${url}`);
                console.log(`  Length: ${resp.text.length}`);
                const rows = parseHtmlTable(resp.text);
                if (rows.length > 0) {
                    console.log(`  Rows: ${rows.length}`);
                    console.log(`  Header: ${JSON.stringify(rows[0])}`);
                    if (rows.length > 1) console.log(`  Row1: ${JSON.stringify(rows[1])}`);
                } else {
                    console.log(`  Text: ${resp.text.substring(0, 400)}`);
                }
            } else {
                console.log(`  [${resp.status}] ${url} (${resp.text.length})`);
            }
        } catch (e) {
            console.log(`  [ERR] ${url}: ${e.message}`);
        }
        await new Promise(r => setTimeout(r, 200));
    }

    // 3. 测试北向资金 API
    console.log(`\n\n=== 测试北向资金 API ===`);
    const hgtApis = [
        `https://data.10jqka.com.cn/hgt/hgtb/ajax/1/`,
        `https://data.10jqka.com.cn/hgt/hgtb/field/zdf/order/desc/ajax/1/`,
        `https://data.10jqka.com.cn/hgt/hgtb/page/1/ajax/1/`,
        `https://data.10jqka.com.cn/hgt/hgtb/report/${dateStr}/ajax/1/`,
        `https://data.10jqka.com.cn/hgt/hgtb/report/${dateStr2}/ajax/1/`,
        `https://data.10jqka.com.cn/hgt/api/zjlx/boardType/5/page/1/ajax/1/`,
        `https://data.10jqka.com.cn/hgt/api/zjlx/boardType/6/page/1/ajax/1/`,
        `https://data.10jqka.com.cn/hgt/api/zjlx/boardType/8/page/1/ajax/1/`,
        `https://data.10jqka.com.cn/hgt/api/zjlx/page/1/ajax/1/`,
        `https://data.10jqka.com.cn/funds/hgt/field/zdf/order/desc/ajax/1/free/1/`,
        `https://data.10jqka.com.cn/funds/hgt/board/5/field/zdf/order/desc/ajax/1/free/1/`,
        `https://data.10jqka.com.cn/funds/hgt/board/6/field/zdf/order/desc/ajax/1/free/1/`,
    ];

    const token2 = await getToken();
    for (const url of hgtApis) {
        try {
            const resp = await fetch(url, {
                ...H("https://data.10jqka.com.cn/hgt/hgtb/"),
                "hexin-v": token2,
            });
            if (resp.status === 200 && resp.text.length > 200) {
                console.log(`\n[OK] ${url}`);
                console.log(`  Length: ${resp.text.length}`);
                const rows = parseHtmlTable(resp.text);
                if (rows.length > 0) {
                    console.log(`  Rows: ${rows.length}`);
                    console.log(`  Header: ${JSON.stringify(rows[0])}`);
                    if (rows.length > 1) console.log(`  Row1: ${JSON.stringify(rows[1])}`);
                } else {
                    console.log(`  Text: ${resp.text.substring(0, 400)}`);
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
