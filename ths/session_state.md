# 同花顺逆向工作会话状态

> 最后更新：2026-08-23 11:00
> 状态：第二步完成，准备进入第三步

---

## 一、环境状态

### 物理机
- OS: Windows 10 (19045.7291)
- IP: 192.168.3.27
- mitmproxy: 11.0.2 (运行中，端口 8080)
- mitmweb: 运行中 (http://127.0.0.1:8081)
- Wireshark: ❌ 未安装（重启后安装）
- OpenSSL: 可用

### 模拟器
- 软件: 雷电模拟器 9
- Android: 9 (API 28)
- ROOT: ✅ 已开启
- ADB: emulator-5556
- 模拟器 IP: 172.16.1.64
- 主网络接口: wlan0
- tcpdump: ✅ /system/xbin/tcpdump (v4.9.2)

### 目标 App
- 同花顺 v10.01.02 (2020年老版本)
- 包名: com.hexin.plat.android
- L2 权限: ✅ 已登录正版账号
- 特点: 无 libhssl-2.1.so，无 libjiagu.so

---

## 二、已完成步骤

### 第一步：系统根证书写入 ✅
- 证书哈希: c8750f0d
- 文件位置: /system/etc/security/cacerts/c8750f0d.0
- 权限: 644 (-rw-r--r--)
- 持久化: 使用 bind mount（重启后需重新绑定）
- 备份位置: /data/local/tmp/cacerts/c8750f0d.0

### 第二步：mitmproxy 代理 + HTTPS 抓包 ✅
- 模拟器代理: 192.168.3.27:8080
- 插件: ths_capture.py（只拦截同花顺，其他放行）
- 验证结果: HTTPS 流量正常解密，无证书错误
- 可抓数据: 新闻/配置/Feed/用户交互/报价快照
- 不可抓数据: 十档盘口/逐笔成交/委托队列（走 TCP）

### 第三步：TCP 私有协议（进行中）
- 状态: Wireshark 未安装，等待安装后抓包
- 目标端口: 9528, 8887
- 帧格式: Magic 0xFDFDFDFD + Length(4B) + Header + Payload

---

## 三、关键发现

### 同花顺 v10.01.02 流量特征
| 类型 | 协议 | 域名/端口 | 可抓性 |
|------|------|-----------|--------|
| 新闻/配置 | HTTPS | eq.10jqka.com.cn | ✅ mitmproxy |
| 首页 Feed | HTTPS | recommend.10jqka.com.cn | ✅ mitmproxy |
| 用户交互 | HTTPS | bbsclick.10jqka.com.cn | ✅ mitmproxy |
| 报价快照 | HTTPS | bi.10jqka.com.cn | ✅ mitmproxy |
| 十档盘口 | TCP | 9528/8887 | ❌ 需 tcpdump |
| 逐笔成交 | TCP | 9528/8887 | ❌ 需 tcpdump |
| 委托队列 | TCP | 9528/8887 | ❌ 需 tcpdump |

### 独家经验（已验证）
1. 股票代码以 ASCII 裸编码存在于 TCP 报文
2. C 语言结构体直接二进制序列化
3. 价格字段 = 实际价格 × 100（分为单位）
4. 固定字节 = 协议头/股票代码/市场标识
5. 变化字节 = 价格/成交量/时间戳

---

## 四、路径速查

```
项目根目录: D:\Development\IDEAWorkSpace\Github\new\crawler-backend
成功文档:   D:\Development\IDEAWorkSpace\Github\new\crawler-backend\ths\success\
mitmproxy:  D:\Development\IDEAWorkSpace\Github\new\crawler-backend\ths\mitm\
ADB:        D:\leidian\LDPlayer9\adb.exe
证书:       C:\Users\Administrator\AppData\Local\Temp\c8750f0d.0
mitmproxy:  C:\Users\Administrator\.mitmproxy\
桌面抓包:   C:\Users\Administrator\Desktop\ths_l2.pcap
```

---

## 五、重启后恢复清单

### 必须重新执行
1. 模拟器 ROOT: `adb -s emulator-5556 root`
2. 证书绑定: `adb shell "mount --bind /data/local/tmp/cacerts /system/etc/security/cacerts"`
3. 代理设置: `adb shell "settings put global http_proxy 192.168.3.27:8080"`
4. 启动 mitmproxy: `mitmweb -p 8080 -s ths_capture.py --web-host 127.0.0.1 --web-port 8081`

### 一次性操作（首次）
1. 安装 Wireshark: https://www.wireshark.org/download.html
2. 安装时勾选 Npcap

---

## 六、当前待办

- [ ] 安装 Wireshark
- [ ] 重启后恢复环境（ROOT + 证书绑定 + 代理）
- [ ] 启动 mitmproxy
- [ ] 运行 capture_ths_tcp.bat 抓包
- [ ] Wireshark 分析 pcap
- [ ] 解析二进制帧格式
- [ ] 编写 Python 私有协议客户端
