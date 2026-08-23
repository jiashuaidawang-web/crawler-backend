/**
 * 分析页面内联 JS + 用 curl 下载外部 JS
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
        const tmpFile = path.join(__dirname, '_tmp_js_' + Date.now() + '.js');
        execSync(`curl -sL --max-time 10 -o "${tmpFile}" "${url}"`, { timeout: 15000 });
        const content = fs.readFileSync(tmpFile, 'utf-8');
        fs.unlinkSync(tmpFile);
        return content;
    } catch (e) {
        return null;
    }
}

function searchApiPatterns(jsText, label) {
    const results = [];

    // 找所有 URL 路径
    const urlPatterns = jsText.match(/["']\/[^"']+["']/g) || [];
    const apiCandidates = urlPatterns.filter(u =>
        u.includes('ajax') || u.includes('api') || u.includes('json') ||
        u.includes('longhu') || u.includes('hgt') || u.includes('hsgt') ||
        u.includes('funds') || u.includes('zjlx') || u.includes('data') ||
        u.includes('rzrq') || u.includes('table')
    );
    if (apiCandidates.length > 0) {
        results.push(`  API URL candidates (${apiCandidates.length}):`);
        [...new Set(apiCandidates)].slice(0, 30).forEach(u => results.push(`    ${u}`));
    }

    // 找 url: "..." 或 url: '...'
    const urlColons = jsText.match(/url\s*:\s*["'][^"']+["']/gi) || [];
    if (urlColons.length > 0) {
        results.push(`  url: patterns (${urlColons.length}):`);
        [...new Set(urlColons)].slice(0, 20).forEach(m => results.push(`    ${m}`));
    }

    // 找 axios.get/post
    const axiosCalls = jsText.match(/axios\.(get|post|put|delete)\s*\([^)]*\)/gi) || [];
    if (axiosCalls.length > 0) {
        results.push(`  axios calls (${axiosCalls.length}):`);
        [...new Set(axiosCalls)].slice(0, 20).forEach(m => results.push(`    ${m.substring(0, 200)}`));
    }

    // 找 $.ajax
    const ajaxCalls = jsText.match(/\$\.(ajax|get|getJSON|post)\s*\([^)]*\)/gi) || [];
    if (ajaxCalls.length > 0) {
        results.push(`  jQuery ajax (${ajaxCalls.length}):`);
        [...new Set(ajaxCalls)].slice(0, 20).forEach(m => results.push(`    ${m.substring(0, 200)}`));
    }

    // 找 tableAjax
    const tableAjax = jsText.match(/tableAjax[^;]{0,200}/gi) || [];
    if (tableAjax.length > 0) {
        results.push(`  tableAjax (${tableAjax.length}):`);
        [...new Set(tableAjax)].slice(0, 20).forEach(m => results.push(`    ${m.substring(0, 200)}`));
    }

    // 找 load/loadData/getData
    const loadFns = jsText.match(/(load|loadData|getData|fetchData|queryData|requestData)[^;]{0,200}/gi) || [];
    if (loadFns.length > 0) {
        results.push(`  load functions (${loadFns.length}):`);
        [...new Set(loadFns)].slice(0, 20).forEach(m => results.push(`    ${m.substring(0, 200)}`));
    }

    return results;
}

async function main() {
    const token = await getToken();
    console.log(`Token: ${token}\n`);

    const H = (referer) => ({
        "Accept": "text/html,application/xhtml+xml",
        "Accept-Encoding": "gzip, deflate",
        "Accept-Language": "zh-CN,zh;q=0.9",
        "Host": "data.10jqka.com.cn",
        "hexin-v": token,
        "Referer": referer,
        "User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36",
    });

    const pages = [
        { name: "龙虎榜", url: "https://data.10jqka.com.cn/market/longhu/", inlineOnly: false },
        { name: "北向资金", url: "https://data.10jqka.com.cn/hgt/hgtb/", inlineOnly: false },
    ];

    for (const page of pages) {
        console.log(`\n${"=".repeat(70)}`);
        console.log(`[${page.name}] ${page.url}`);
        console.log("=".repeat(70));

        const resp = await fetch(page.url, H("https://data.10jqka.com.cn/"));
        console.log(`Status: ${resp.status}, Length: ${resp.text.length}`);
        if (resp.status !== 200) continue;

        // 1. 分析内联 JS
        console.log(`\n--- 内联 JS 分析 ---`);
        const inlineScripts = resp.text.match(/<script[^>]*>([\s\S]*?)<\/script>/gi) || [];
        console.log(`Found ${inlineScripts.length} inline scripts`);
        for (const script of inlineScripts) {
            const jsContent = script.replace(/<script[^>]*>/i, '').replace(/<\/script>/i, '');
            if (jsContent.trim().length > 20) {
                const results = searchApiPatterns(jsContent, 'inline');
                if (results.length > 0) {
                    console.log(`\nInline script (${jsContent.length} chars):`);
                    results.forEach(r => console.log(r));
                }
            }
        }

        // 2. 用 curl 下载关键 JS 文件
        const jsFilePatterns = resp.text.match(/<script[^>]*src=["']([^"']+)["']/gi) || [];
        console.log(`\n--- 外部 JS 文件 (${jsFilePatterns.length}) ---`);
        for (const s of jsFilePatterns) {
            const m = s.match(/src=["']([^"']+)["']/);
            if (!m) continue;
            let url = m[1];
            if (url.startsWith('//')) url = 'https:' + url;
            else if (url.startsWith('/')) url = new URL(url, page.url).href;
            else if (!url.startsWith('http')) url = new URL(url, page.url).href;

            // 只分析包含关键词的 JS
            if (url.includes('lhb') || url.includes('longhu') || url.includes('hgt') ||
                url.includes('hsgt') || url.includes('rzrq') || url.includes('tableAjax') ||
                url.includes('funds') || url.includes('datacenter')) {
                console.log(`\n[CURL] ${url}`);
                const jsContent = fetchWithCurl(url);
                if (jsContent) {
                    console.log(`  Downloaded: ${jsContent.length} chars`);
                    const results = searchApiPatterns(jsContent, 'external');
                    results.forEach(r => console.log(r));
                } else {
                    console.log(`  Failed to download`);
                }
            }
        }
    }
}

main().catch(console.error);
