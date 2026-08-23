/**
 * 用 jsdom 模拟浏览器环境，执行 chameleon JS 获取 hexin-v token
 */
const { JSDOM } = require('jsdom');
const fs = require('fs');
const path = require('path');

// 读取 chameleon JS
const jsCode = fs.readFileSync(path.join(__dirname, 'chameleon.1.13.min.js'), 'utf-8');

// 创建 jsdom 环境
const dom = new JSDOM('<!DOCTYPE html><html><body></body></html>', {
    url: 'https://data.10jqka.com.cn/',
    pretendToBeVisual: true,
    runScripts: 'dangerously',
});

const { window } = dom;

// 确保 CHAMELEON_CALLBACK 被定义并捕获 token
let capturedToken = null;
window.CHAMELEON_CALLBACK = function(token) {
    capturedToken = token;
};

// 执行 chameleon JS
try {
    window.eval(jsCode);
} catch (e) {
    console.error('JS eval error:', e.message);
    process.exit(1);
}

// 等待 token 生成（chameleon 可能异步生成）
setTimeout(() => {
    if (capturedToken) {
        console.log(JSON.stringify({ ok: true, token: capturedToken }));
    } else {
        // 尝试从 cookie 获取
        const cookies = window.document.cookie;
        const vMatch = cookies.match(/v=([^;]+)/);
        if (vMatch) {
            console.log(JSON.stringify({ ok: true, token: vMatch[1], source: 'cookie' }));
        } else {
            console.log(JSON.stringify({ ok: false, error: 'no token generated', cookies: cookies }));
        }
    }
    process.exit(0);
}, 2000);
