# 重启后继续工作 - 提示词

## 直接复制以下提示词给 Claude：

---

我正在进行同花顺 v10.01.02 的 L2 数据抓取项目。

环境：
- 雷电模拟器 9 + Android 9 + ROOT
- 同花顺 v10.01.02（已登录 L2 账号）
- mitmproxy 代理在 8080 端口
- 物理机 IP: 192.168.3.27

当前进度：
1. ✅ 第一步完成：mitmproxy 证书已写入模拟器系统根证书（bind mount 方式）
2. ✅ 第二步完成：mitmproxy 代理已配置，确认同花顺 HTTPS 流量可解密
3. ✅ 关键发现：十档盘口和逐笔成交走 TCP 私有协议（端口 9528/8887），不走 HTTPS
4. ⏳ 第三步进行中：需要安装 Wireshark 并抓取 TCP 私有协议包

刚完成电脑重启，需要：
1. 恢复模拟器 ROOT 权限和证书绑定
2. 安装 Wireshark
3. 启动 mitmproxy
4. 抓取同花顺 TCP 私有协议包
5. 用 Wireshark 分析帧格式

ADB 路径：D:\leidian\LDPlayer9\adb.exe
模拟器设备：emulator-5556
项目目录：D:\Development\IDEAWorkSpace\Github\new\crawler-backend\ths\success\

请帮我恢复环境并开始第三步的 TCP 抓包工作。

---
