# 同花顺抓包环境配置指南（已验证成功）

> 更新日期：2026-08-23

## 一、环境清单

| 项目 | 版本/配置 |
|------|----------|
| 模拟器 | 雷电模拟器 9 |
| Android 版本 | Android 9 (API 28) |
| 同花顺版本 | v10.01.02 (2020年老版本) |
| ROOT 权限 | 雷电设置中开启 |
| mitmproxy | 11.0.2 |
| 物理机 OS | Windows 10 |

## 二、为什么选这个版本

- **v10.01.02 无加固壳**：没有 libhssl-2.1.so、libjiagu.so 等对抗
- **HTTPS 可抓包**：大部分接口（新闻/配置/自选股）走 HTTPS，可被 mitmproxy 解密
- **L2 仍走 TCP**：十档盘口/逐笔成交走私有 TCP 协议（端口 9528/8887），不走 HTTPS
- **账号要求**：需购买正版 L2 权限账号并登录

## 三、环境搭建步骤

### 3.1 模拟器准备

1. 安装雷电模拟器 9
2. 创建 Android 9 实例
3. 设置 → 开启 ROOT 权限 → 保存并重启
4. 安装同花顺 v10.01.02 APK
5. 启动同花顺，登录 L2 账号

### 3.2 物理机准备

1. 安装 mitmproxy: `pip install mitmproxy`
2. 首次运行 `mitmproxy` 生成证书
3. 证书位置: `%USERPROFILE%\.mitmproxy\mitmproxy-ca-cert.pem`

### 3.3 写入系统根证书

```batch
:: 1. 计算证书哈希
openssl x509 -inform PEM -subject_hash_old -in "%USERPROFILE%\.mitmproxy\mitmproxy-ca-cert.pem" -noout
:: 输出: c8750f0d

:: 2. 复制为哈希文件名
copy "%USERPROFILE%\.mitmproxy\mitmproxy-ca-cert.pem" "%USERPROFILE%\.mitmproxy\c8750f0d.0"

:: 3. 获取 ROOT 权限
adb root
adb wait-for-device

:: 4. 推送到模拟器临时目录
adb push "%USERPROFILE%\.mitmproxy\c8750f0d.0" /data/local/tmp/c8750f0d.0

:: 5. 备份整个 cacerts 目录并注入新证书
adb shell "mkdir -p /data/local/tmp/cacerts"
adb shell "cp /system/etc/security/cacerts/* /data/local/tmp/cacerts/"
adb shell "cp /data/local/tmp/c8750f0d.0 /data/local/tmp/cacerts/"
adb shell "chmod 644 /data/local/tmp/cacerts/c8750f0d.0"

:: 6. 绑定挂载（绕过 /system 只读限制）
adb shell "mount --bind /data/local/tmp/cacerts /system/etc/security/cacerts"

:: 7. 验证
adb shell "ls -la /system/etc/security/cacerts/c8750f0d.0"
```

**⚠️ 注意**：重启后 bind mount 会丢失，需重新执行步骤 6。

### 3.4 配置模拟器代理

```batch
:: 设置全局代理（仅模拟器生效，不影响 Windows）
adb shell settings put global http_proxy 192.168.3.27:8080

:: 验证
adb shell settings get global http_proxy
```

### 3.5 启动 mitmproxy

```powershell
# 启动 mitmweb（带插件，仅拦截同花顺流量）
mitmweb -p 8080 -s C:\Users\Administrator\Desktop\ths_mitm\ths_capture.py --web-host 127.0.0.1 --web-port 8081
```

可视化界面: http://127.0.0.1:8081

## 四、可抓到的数据（HTTPS）

| 数据类型 | 域名 | 路径示例 |
|---------|------|---------|
| 新闻/资讯 | eq.10jqka.com.cn | /wencai/data/config/close.txt |
| 配置同步 | eq.10jqka.com.cn | /hub/syncData/starMarketConfig/ |
| 首页 Feed | recommend.10jqka.com.cn | /feed/api/v1/index |
| 用户交互 | bbsclick.10jqka.com.cn | /getVote |
| 实时报价 | bi.10jqka.com.cn | /{stockCode}_{marketId}/current_latest.json |
| 模板配置 | ozone.10jqka.com.cn | /tg_templates/hangqing/ |

## 五、不可抓到的数据（TCP 私有协议）

| 数据类型 | 端口 | 协议 |
|---------|------|------|
| 十档盘口 (Depth) | 9528 / 8887 | 私有二进制 |
| 逐笔成交 (Tick) | 9528 / 8887 | 私有二进制 |
| 委托队列 (Order Queue) | 9528 / 8887 | 私有二进制 |
| 分时走势推送 | 9528 / 8887 | 私有二进制 |

**帧格式**: Magic `0xFDFDFDFD` (4B) + Length (4B 小端) + Header (?) + Payload

## 六、网络拓扑

```
模拟器 (172.16.1.64)
  │
  ├── WiFi 代理 ──→ 物理机 mitmproxy (192.168.3.27:8080)
  │                   └── ths_capture.py 插件
  │                         ├── 同花顺 HTTPS → 解析打印
  │                         └── 其他流量 → 透传放行
  │
  └── 同花顺私有 TCP ────→ 28.137.134.8:9528（行情）
                          235.67.94.1:8887（行情）
                          140.207.55.20:443（WebSocket）
```

## 七、关键 ADB 命令速查

```powershell
# 获取 ROOT
D:\leidian\LDPlayer9\adb.exe -s emulator-5556 root

# 重新绑定证书（重启后）
D:\leidian\LDPlayer9\adb.exe -s emulator-5556 shell "mount --bind /data/local/tmp/cacerts /system/etc/security/cacerts"

# 设置代理
D:\leidian\LDPlayer9\adb.exe -s emulator-5556 shell "settings put global http_proxy 192.168.3.27:8080"

# 清除代理
D:\leidian\LDPlayer9\adb.exe -s emulator-5556 shell "settings put global http_proxy :0"

# 查看模拟器 IP 路由
D:\leidian\LDPlayer9\adb.exe -s emulator-5556 shell "ip route"

# 测试连通性
D:\leidian\LDPlayer9\adb.exe -s emulator-5556 shell "ping -c 2 192.168.3.27"
```

## 八、常见问题

| 问题 | 原因 | 解决 |
|------|------|------|
| `adb remount` 失败 | Android 9 dm-verity | 用 `mount --bind` 替代 |
| `/system` 只读 | squashfs/overlay | bind mount 绕过 |
| 重启后证书丢失 | bind mount 不持久 | 重启后重新执行绑定命令 |
| Windows 程序受影响 | 误设系统代理 | 只设模拟器代理，不动 Windows |
| mitmproxy 无流量 | 模拟器代理未生效 | 检查 `settings get global http_proxy` |

## 九、下一步：L2 数据抓取

由于 L2 走 TCP 私有协议，需要：

1. **tcpdump 抓包**: `adb shell tcpdump -i any -w /data/local/tmp/l2.pcap port 9528 or port 8887`
2. **Wireshark 分析**: 导入 pcap，过滤 `tcp.port == 9528 || tcp.port == 8887`
3. **解析帧格式**: Magic `0xFDFDFDFD` → Length → Header → Payload
4. **编写客户端**: 用 Python socket 连接并解析二进制流
