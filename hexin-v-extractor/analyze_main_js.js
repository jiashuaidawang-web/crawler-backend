/**
 * 深入分析 main_v3.js 的 AJAX 调用
 */
const fs = require('fs');
const path = require('path');

const mainJs = fs.readFileSync(path.join(__dirname, '_main_v3.js'), 'utf-8');

// 找所有 $.ajax 调用的完整上下文
const ajaxRegex = /\$\.ajax\s*\([^}]*\}/g;
let match;
let count = 0;
while ((match = ajaxRegex.exec(mainJs)) !== null) {
    count++;
    console.log(`\n=== Ajax Call ${count} ===`);
    // 打印前后文
    const start = Math.max(0, match.index - 200);
    const end = Math.min(mainJs.length, match.index + match[0].length + 200);
    console.log(mainJs.substring(start, end));
}

// 找所有 url: 模式
console.log(`\n\n=== All url: patterns ===`);
const urlRegex = /url\s*:\s*["'][^"']+["']/g;
count = 0;
while ((match = urlRegex.exec(mainJs)) !== null) {
    count++;
    console.log(`${count}: ${match[0]}`);
}

// 找所有 ajaxUrl
console.log(`\n\n=== All ajaxUrl patterns ===`);
const ajaxUrlRegex = /ajaxUrl[^;]{0,100}/g;
count = 0;
while ((match = ajaxUrlRegex.exec(mainJs)) !== null) {
    count++;
    console.log(`${count}: ${match[0]}`);
}

// 找 hgt/hsgt/rzrq 相关
console.log(`\n\n=== hgt/hsgt/rzrq contexts ===`);
const hgtRegex = /.{0,100}(hgt|hsgt|rzrq|getHgtPage|getSgtPage|GgtPage).{0,100}/g;
count = 0;
while ((match = hgtRegex.exec(mainJs)) !== null) {
    count++;
    if (count <= 30) {
        console.log(`${count}: ${match[0]}`);
    }
}

// 找 param 相关
console.log(`\n\n=== param contexts ===`);
const paramRegex = /.{0,50}param.{0,150}/g;
count = 0;
while ((match = paramRegex.exec(mainJs)) !== null) {
    count++;
    if (count <= 20) {
        console.log(`${count}: ${match[0]}`);
    }
}
