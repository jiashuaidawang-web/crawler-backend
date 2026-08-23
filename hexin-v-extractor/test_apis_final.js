/**
 * 最终 API 测试 - 龙虎榜 + 北向资金
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

    const dateStr = "2026-08-22";
    const dateStr2 = "20260822";

    // ========================
    // 龙虎榜 - 详细测试
    // ========================
    console.log("=".repeat(70));
    console.log("龙虎榜 API 详细测试");
    console.log("=".repeat(70));

    // 龙虎榜股票列表 - 不同参数
    const lhbApis = [
        // 基础 - 已验证可用
        { name: "龙虎榜-默认", url: `https://data.10jqka.com.cn/ifmarket/lhbtable/stock/all/ajax/1/` },
        // 带日期
        { name: "龙虎榜-日期1", url: `https://data.10jqka.com.cn/ifmarket/lhbtable/stock/all/report/${dateStr}/ajax/1/` },
        { name: "龙虎榜-日期2", url: `https://data.10jqka.com.cn/ifmarket/lhbtable/stock/all/report/${dateStr2}/ajax/1/` },
        // 带分页
        { name: "龙虎榜-p1", url: `https://data.10jqka.com.cn/ifmarket/lhbtable/stock/all/page/1/ajax/1/` },
        { name: "龙虎榜-p2", url: `https://data.10jqka.com.cn/ifmarket/lhbtable/stock/all/page/2/ajax/1/` },
        // 带排序
        { name: "龙虎榜-排序", url: `https://data.10jqka.com.cn/ifmarket/lhbtable/stock/all/field/zdf/order/desc/page/1/ajax/1/` },
        // 3日
        { name: "龙虎榜-3日", url: `https://data.10jqka.com.cn/ifmarket/lhbtable/stock/3/page/1/ajax/1/` },
        // 营业部
        { name: "营业部-默认", url: `https://data.10jqka.com.cn/ifmarket/lhbyyb/ajax/1/` },
        { name: "营业部-日期", url: `https://data.10jqka.com.cn/ifmarket/lhbyyb/report/${dateStr}/ajax/1/` },
        { name: "营业部-p1", url: `https://data.10jqka.com.cn/ifmarket/lhbyyb/page/1/ajax/1/` },
        { name: "营业部-p2", url: `https://data.10jqka.com.cn/ifmarket/lhbyyb/page/2/ajax/1/` },
        // 个股详情
        { name: "个股详情", url: `https://data.10jqka.com.cn/ifmarket/lhbggxq/report/${dateStr}/ajax/1/` },
    ];

    for (const api of lhbApis) {
        try {
            const resp = await fetch(api.url, H("https://data.10jqka.com.cn/market/longhu/"));
            if (resp.status === 200 && resp.text.length > 100) {
                console.log(`\n[OK] ${api.name}`);
                console.log(`  URL: ${api.url}`);
                console.log(`  Length: ${resp.text.length}`);
                const rows = parseHtmlTable(resp.text);
                if (rows.length > 0) {
                    console.log(`  Rows: ${rows.length}`);
                    console.log(`  Header: ${JSON.stringify(rows[0])}`);
                    for (let i = 1; i < Math.min(rows.length, 4); i++) {
                        console.log(`  Row${i}: ${JSON.stringify(rows[i])}`);
                    }
                } else {
                    console.log(`  Text: ${resp.text.substring(0, 300)}`);
                }
            } else {
                console.log(`  [${resp.status}] ${api.name} (${resp.text.length})`);
            }
        } catch (e) {
            console.log(`  [ERR] ${api.name}: ${e.message}`);
        }
        await new Promise(r => setTimeout(r, 200));
    }

    // ========================
    // 北向资金 - 深入分析
    // ========================
    console.log(`\n\n${"=".repeat(70)}`);
    console.log("北向资金 API 深入分析");
    console.log("=".repeat(70));

    // 获取北向资金页面源码，仔细分析
    const hgtResp = await fetch("https://data.10jqka.com.cn/hgt/hgtb/", {
        "Accept": "text/html,application/xhtml+xml",
        "Accept-Encoding": "gzip, deflate",
        "Accept-Language": "zh-CN,zh;q=0.9",
        "Host": "data.10jqka.com.cn",
        "hexin-v": token,
        "Referer": "https://data.10jqka.com.cn/",
        "User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36",
    });

    console.log(`页面 Status: ${hgtResp.status}, Length: ${hgtResp.text.length}`);

    // 找所有内联 JS 中的 API 相关代码
    const inlineScripts = hgtResp.text.match(/<script[^>]*>([\s\S]*?)<\/script>/gi) || [];
    for (const script of inlineScripts) {
        const jsContent = script.replace(/<script[^>]*>/i, '').replace(/<\/script>/i, '');
        if (jsContent.includes('ajax') || jsContent.includes('url') || jsContent.includes('api') || jsContent.includes('hgt')) {
            console.log(`\n--- 相关内联 JS (${jsContent.length} chars) ---`);
            // 找关键行
            const lines = jsContent.split('\n');
            for (const line of lines) {
                if (line.includes('ajax') || line.includes('url') || line.includes('api') || line.includes('hgt') || line.includes('table')) {
                    console.log(`  ${line.trim().substring(0, 200)}`);
                }
            }
        }
    }

    // 下载并分析 rzrq/main_v3.js (北向资金页面引用的)
    console.log(`\n--- 分析 rzrq/main_v3.js ---`);
    const mainJs = fetchWithCurl("https://s.thsi.cn/js/datacenter/rzrq/main_v3.js");
    if (mainJs) {
        const contexts = mainJs.match(/.{0,100}(ajax|ajaxUrl|hgt|hsgt|zjlx|tableAjax).{0,100}/gi) || [];
        console.log(`Found ${contexts.length} contexts:`);
        contexts.forEach(c => console.log(`  ${c}`));
    }

    // 分析 dataChartV2.js
    console.log(`\n--- 分析 dataChartV2.js ---`);
    const chartJs = fetchWithCurl("https://s.thsi.cn/website/datacenter/rzrq/dataChartV2.js?_=20250327");
    if (chartJs) {
        const contexts = chartJs.match(/.{0,100}(ajax|ajaxUrl|hgt|hsgt|zjlx|data|chart).{0,100}/gi) || [];
        console.log(`Found ${contexts.length} contexts:`);
        contexts.forEach(c => console.log(`  ${c}`));
    }

    // 尝试更多北向资金 URL
    console.log(`\n--- 尝试北向资金 URL ---`);
    const hgtApis = [
        `https://data.10jqka.com.cn/hgt/hgtb/stock/all/ajax/1/`,
        `https://data.10jqka.com.cn/hgt/hgtb/stock/all/report/${dateStr}/ajax/1/`,
        `https://data.10jqka.com.cn/hgt/hgtb/stock/all/report/${dateStr2}/ajax/1/`,
        `https://data.10jqka.com.cn/hgt/hgtb/page/1/ajax/1/free/1/`,
        `https://data.10jqka.com.cn/hgt/hgtb/field/zdf/order/desc/page/1/ajax/1/free/1/`,
        `https://data.10jqka.com.cn/hgt/hgtb/report/${dateStr}/page/1/ajax/1/`,
        `https://data.10jqka.com.cn/hgt/hgtb/report/${dateStr2}/page/1/ajax/1/`,
        `https://data.10jqka.com.cn/hgt/hgtb/report/${dateStr}/field/zdf/order/desc/page/1/ajax/1/`,
        `https://data.10jqka.com.cn/hgt/hgtb/report/${dateStr2}/field/zdf/order/desc/page/1/ajax/1/`,
        // 尝试不同的日期格式
        `https://data.10jqka.com.cn/hgt/hgtb/report/20260822/page/1/ajax/1/free/1/`,
        `https://data.10jqka.com.cn/hgt/hgtb/report/2026-08-22/page/1/ajax/1/free/1/`,
    ];

    const token2 = await getToken();
    for (const url of hgtApis) {
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
                    console.log(`  Text: ${resp.text.substring(0, 300)}`);
                }
            } else if (resp.status !== 404) {
                console.log(`  [${resp.status}] ${url} (${resp.text.length})`);
                if (resp.text.length > 0 && resp.text.length < 200) {
                    console.log(`    ${resp.text}`);
                }
            }
        } catch (e) {
            console.log(`  [ERR] ${url}: ${e.message}`);
        }
        await new Promise(r => setTimeout(r, 200));
    }
}

main().catch(console.error);
