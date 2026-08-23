// Token 生成脚本 - 被 token_manager.py 调用
const { JSDOM } = require('jsdom');
const fs = require('fs');
const path = require('path');

const jsFile = process.argv[2] || path.join(__dirname, 'chameleon.1.13.min.js');
const jsCode = fs.readFileSync(jsFile, 'utf-8');

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
    if (capturedToken) {
        console.log(capturedToken);
        process.exit(0);
    } else {
        const cookies = window.document.cookie;
        const vMatch = cookies.match(/v=([^;]+)/);
        if (vMatch) {
            console.log(vMatch[1]);
            process.exit(0);
        } else {
            console.error('NO_TOKEN');
            process.exit(1);
        }
    }
}, 3000);
