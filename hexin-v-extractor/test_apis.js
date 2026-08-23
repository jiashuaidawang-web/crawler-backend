/**
 * 用获取到的 token 测试同花顺资金流向 API
 */
const { JSDOM } = require('jsdom');
const http = require('http');
const https = require('https');
const fs = require('fs');
const path = require('path');

// 获取 token
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

// HTTP 请求
function fetch(url, headers) {
    return new Promise((resolve, reject) => {
        const mod = url.startsWith('https') ? https : http;
        const req = mod.get(url, { headers }, (res) => {
            let data = '';
            res.on('data', chunk => data += chunk);
            res.on('end', () => resolve({ status: res.statusCode, text: data, headers: res.headers }));
        });
        req.on('error', reject);
        req.setTimeout(15000, () => { req.destroy(); reject(new Error('timeout')); });
    });
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
        // 北向资金
        { name: "北向-汇总", url: "https://data.10jqka.com.cn/hsgt/api/zjlx/", ref: "https://data.10jqka.com.cn/hsgt/" },
        { name: "北向-个股", url: "https://data.10jqka.com.cn/hsgt/api/zjlx/boardType/5/page/1/ajax/1/", ref: "https://data.10jqka.com.cn/hsgt/" },
        { name: "北向-板块6", url: "https://data.10jqka.com.cn/hsgt/api/zjlx/boardType/6/", ref: "https://data.10jqka.com.cn/hsgt/" },
        // 板块资金
        { name: "行业资金-p1", url: "http://data.10jqka.com.cn/funds/hyzjl/field/tradezdf/order/desc/ajax/1/free/1/", ref: "http://data.10jqka.com.cn/funds/hyzjl/" },
        { name: "行业资金-p2", url: "http://data.10jqka.com.cn/funds/hyzjl/field/tradezdf/order/desc/page/2/ajax/1/free/1/", ref: "http://data.10jqka.com.cn/funds/hyzjl/" },
        { name: "概念资金-p1", url: "http://data.10jqka.com.cn/funds/gnzjl/field/tradezdf/order/desc/ajax/1/free/1/", ref: "http://data.10jqka.com.cn/funds/gnzjl/" },
        { name: "概念资金-p2", url: "http://data.10jqka.com.cn/funds/gnzjl/field/tradezdf/order/desc/page/2/ajax/1/free/1/", ref: "http://data.10jqka.com.cn/funds/gnzjl/" },
        // 龙虎榜
        { name: "龙虎榜-当日", url: "https://data.10jqka.com.cn/market/lhb/cxg/", ref: "https://data.10jqka.com.cn/market/lhb/" },
        { name: "龙虎榜-统计", url: "https://data.10jqka.com.cn/market/lhb/statistics/", ref: "https://data.10jqka.com.cn/market/lhb/" },
        { name: "龙虎榜-席位", url: "https://data.10jqka.com.cn/market/lhb/seat/", ref: "https://data.10jqka.com.cn/market/lhb/" },
        { name: "龙虎榜-ajax", url: "https://data.10jqka.com.cn/market/lhb/cxg/ajax/1/", ref: "https://data.10jqka.com.cn/market/lhb/" },
        // 个股资金
        { name: "个股资金-p1", url: "http://data.10jqka.com.cn/funds/ggzjl/field/zdf/order/desc/ajax/1/free/1/", ref: "http://data.10jqka.com.cn/funds/ggzjl/" },
        { name: "个股资金-p2", url: "http://data.10jqka.com.cn/funds/ggzjl/field/zdf/order/desc/page/2/ajax/1/free/1/", ref: "http://data.10jqka.com.cn/funds/ggzjl/" },
        { name: "个股资金-3日", url: "http://data.10jqka.com.cn/funds/ggzjl/board/3/field/zdf/order/desc/ajax/1/free/1/", ref: "http://data.10jqka.com.cn/funds/ggzjl/" },
        { name: "大单追踪", url: "http://data.10jqka.com.cn/funds/ddzz/order/desc/ajax/1/free/1/", ref: "http://data.10jqka.com.cn/funds/ddzz/" },
    ];

    for (const api of apis) {
        console.log(`\n[${api.name}] ${api.url}`);
        try {
            const resp = await fetch(api.url, H(api.ref));
            const text = resp.text;
            console.log(`  Status: ${resp.status}, Length: ${text.length}`);

            if (resp.status === 200 && text.length > 100) {
                if (text.trim().startsWith('{') || text.trim().startsWith('[')) {
                    try {
                        const data = JSON.parse(text);
                        console.log(`  JSON keys: ${typeof data === 'object' ? Object.keys(data).join(',') : typeof data}`);
                        console.log(`  Preview: ${JSON.stringify(data).substring(0, 400)}`);
                    } catch {
                        console.log(`  Text: ${text.substring(0, 300)}`);
                    }
                } else {
                    // HTML - 提取表格行
                    const rows = text.match(/<tr[^>]*>(.*?)<\/tr>/gs) || [];
                    console.log(`  HTML rows: ${rows.length}`);
                    if (rows.length > 0) {
                        // 提取第一行数据
                        const cells = rows[0].match(/<td[^>]*>(.*?)<\/td>/gs) || [];
                        const clean = cells.map(c => c.replace(/<[^>]+>/g, '').trim()).slice(0, 10);
                        console.log(`  First row: ${JSON.stringify(clean)}`);
                    }
                    console.log(`  Preview: ${text.substring(0, 200)}`);
                }
            } else {
                console.log(`  Response: ${text.substring(0, 200)}`);
            }
        } catch (e) {
            console.log(`  Error: ${e.message}`);
        }

        // 延迟避免被封
        await new Promise(r => setTimeout(r, 300));
    }
}

main().catch(console.error);
