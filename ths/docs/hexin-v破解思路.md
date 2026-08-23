# 同花顺 hexin-v Token 破解思路

## 背景
同花顺网站（data.10jqka.com.cn）的 API 需要 `hexin-v` token 才能访问。
这个 token 是由一段名为 "chameleon" 的 JavaScript 在浏览器端生成的。

## 破解步骤

### 第一步：发现 token 的存在
- 用浏览器访问同花顺资金流向页面（如 `data.10jqka.com.cn/funds/hyzjl/`）
- 打开 DevTools → Network → XHR
- 发现每个 AJAX 请求的请求头里都有 `hexin-v` 字段
- 直接复制这个 token 可以用一段时间，但会过期

### 第二步：找到 token 生成 JS
- 查看页面源码，找到 `<script src="//s.thsi.cn/js/chameleon/chameleon.1.13.min.js">`
- 这个 `chameleon.1.13.min.js` 就是生成 token 的核心 JS
- 文件名 "chameleon"（变色龙）暗示它会根据环境变化生成不同的 token

### 第三步：理解 chameleon 的工作原理
chameleon JS 会收集浏览器指纹信息：
- Canvas 渲染指纹
- WebGL 信息
- 字体列表
- 时区、语言
- 屏幕分辨率
- User-Agent

然后用这些信息计算出 token，作为 cookie `v` 或通过回调函数返回。

### 第四步：尝试直接执行 JS

#### 尝试 1：Python 执行（失败）
- 用 py_mini_racer 执行 JS
- 报错：缺少浏览器 API（document、canvas 等）

#### 尝试 2：Playwright/ Puppeteer（失败）
- 安装 Chromium 时平台不匹配（Mac Chromium 装在了 Windows 上）
- 启动缓慢，资源消耗大

#### 尝试 3：jsdom（成功）
- jsdom 是 Node.js 的 DOM 模拟器
- 可以模拟 document、window 等浏览器对象
- chameleon 有降级逻辑：当 canvas 不可用时，用其他方式计算 token
- 产出的 token 与真实浏览器的 token 格式一致，且能正常使用

### 第五步：封装 token 管理器
```javascript
const { JSDOM } = require('jsdom');
const dom = new JSDOM('<!DOCTYPE html><html><body></body></html>', {
    url: 'https://data.10jqka.com.cn/',
    pretendToBeVisual: true,
    runScripts: 'dangerously',
});
const { window } = dom;
window.CHAMELEON_CALLBACK = (token) => { /* 获取 token */ };
window.eval(jsCode);
```

## 如果以后失效怎么办？

### 检查清单
1. **chameleon JS 是否更新？**
   - 访问 `data.10jqka.com.cn` 页面源码
   - 搜索 `chameleon` 看 JS 文件名是否变化（如 `chameleon.1.14.min.js`）
   - 如果有新版本，下载替换即可

2. **jsdom 是否仍然可用？**
   - chameleon 可能增加新的浏览器 API 检测
   - 如果 jsdom 不再支持，可用 Playwright headless Chrome 替代

3. **token 格式是否变化？**
   - 观察 token 长度、字符集
   - 如果是全新算法，需要重新逆向

### 备用方案
1. **Playwright headless Chrome**: 最可靠但最慢
2. **从浏览器手动获取**: 复制 DevTools 里的 token，手动注入
3. **逆向 chameleon 算法**: 分析 JS 源码，用 Python 实现等价逻辑

### 快速恢复步骤
```bash
# 1. 下载最新 chameleon JS
curl -o chameleon.latest.js "https://s.thsi.cn/js/chameleon/chameleon.x.x.min.js"

# 2. 替换旧文件
mv chameleon.latest.js chameleon.1.13.min.js

# 3. 测试 token 获取
node -e "require('./token_manager.js').get_token_manager().get_token()"
```

## 关键文件
- `ths/crawler/chameleon.1.13.min.js` - token 生成 JS（核心资产）
- `ths/crawler/token_manager.py` - token 管理器
- `ths/diaoyan_script/get_token.js` - 原始调研脚本
