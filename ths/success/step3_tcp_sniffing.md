# 第三步：老版本 TCP 私有协议嗅探方案

> 更新日期：2026-08-23
> 前置条件：第一步（证书）、第二步（mitmproxy）已完成

---

## 一、tcpdump 部署与抓包命令

### 1.1 确认 tcpdump 可用

```powershell
# 检查是否自带
D:\leidian\LDPlayer9\adb.exe -s emulator-5556 shell "which tcpdump"
# 输出: /system/xbin/tcpdump

# 检查版本
D:\leidian\LDPlayer9\adb.exe -s emulator-5556 shell "tcpdump --version"
# 输出: tcpdump version 4.9.2
```

### 1.2 如果没有 tcpdump（备用方案）

```powershell
# 下载 Android 版 tcpdump (x86_64 兼容)
# 来源: https://www.androidtcpdump.com/
# 或使用预编译二进制推送到模拟器

# 推送到模拟器
D:\leidian\LDPlayer9\adb.exe -s emulator-5556 push tcpdump.x64 /data/local/tmp/tcpdump
D:\leidian\LDPlayer9\adb.exe -s emulator-5556 shell "chmod 755 /data/local/tmp/tcpdump"
```

### 1.3 核心抓包命令

```powershell
:: 命令 1: 捕获同花顺行情端口（9528/8887）
D:\leidian\LDPlayer9\adb.exe -s emulator-5556 shell "tcpdump -i wlan0 -w /data/local/tmp/ths_l2.pcap tcp port 9528 or tcp port 8887"

:: 命令 2: 捕获所有非 HTTP/HTTPS 的模拟器流量（更宽泛）
D:\leidian\LDPlayer9\adb.exe -s emulator-5556 shell "tcpdump -i wlan0 -w /data/local/tmp/ths_all.pcap not port 80 and not port 443 and not port 8080"

:: 命令 3: 限制包大小（只抓头部 256 字节，加快速度）
D:\leidian\LDPlayer9\adb.exe -s emulator-5556 shell "tcpdump -i wlan0 -s 256 -w /data/local/tmp/ths_header.pcap tcp port 9528 or tcp port 8887"

:: 命令 4: 带时间戳和环形缓冲（抓 50MB × 2 个文件）
D:\leidian\LDPlayer9\adb.exe -s emulator-5556 shell "tcpdump -i wlan0 -w /data/local/tmp/ths_rotate.pcap -C 50 -W 2 tcp port 9528 or tcp port 8887"
```

### 1.4 抓包流程

```powershell
:: 第 1 步: 启动抓包（后台运行）
D:\leidian\LDPlayer9\adb.exe -s emulator-5556 shell "tcpdump -i wlan0 -w /data/local/tmp/ths_l2.pcap tcp port 9528 or tcp port 8887 &"

:: 第 2 步: 打开同花顺，进入十档盘口页面，疯狂刷新 30 秒

:: 第 3 步: 停止抓包
D:\leidian\LDPlayer9\adb.exe -s emulator-5556 shell "pkill tcpdump"

:: 第 4 步: 导出 pcap 到物理机
D:\leidian\LDPlayer9\adb.exe -s emulator-5556 pull /data/local/tmp/ths_l2.pcap C:\Users\Administrator\Desktop\ths_l2.pcap

:: 第 5 步: 用 Wireshark 打开分析
```

---

## 二、Wireshark 过滤表达式与分析策略

### 2.1 基础过滤（排除噪音）

```
# 只看同花顺行情端口
tcp.port == 9528 || tcp.port == 8887

# 排除 mitmproxy 代理流量
tcp.port == 9528 || tcp.port == 8887 && !(ip.addr == 192.168.3.27)

# 只看 TCP payload 非空的数据包
tcp.port == 9528 && tcp.len > 0

# 只看建立连接的 SYN 包（找服务器 IP）
tcp.flags.syn == 1 && tcp.flags.ack == 0 && (tcp.dstport == 9528 || tcp.dstport == 8887)
```

### 2.2 高级分析过滤

```
# 跟踪完整 TCP 流（右键 → Follow → TCP Stream）
tcp.stream eq 0

# 查找包含特定股票代码（ASCII "600000"）
tcp.payload contains "600000"
tcp.payload contains "sh600000"
tcp.payload contains "sz000001"

# 查找 Magic 字节 0xFDFDFDFD
tcp.payload contains fd:fd:fd:fd

# 按包大小过滤（心跳包通常很小）
tcp.len < 50 && tcp.port == 9528

# 只看推送方向（服务器 → 客户端）
ip.src == 28.137.134.8 || ip.src == 235.67.94.1
```

### 2.3 分析流程

```
步骤 1: 打开 pcap → 过滤 tcp.port == 9528 || tcp.port == 8887
步骤 2: 看 Statistics → Conversations → TCP Tab，找到活跃连接
步骤 3: 右键活跃连接 → Follow TCP Stream，看原始字节流
步骤 4: 搜索 ASCII 字符串（Edit → Find Packet → String → "600000"）
步骤 5: 对比连续多个包的 Hex 差异（找出变化的字段 = 价格/成交量）
步骤 6: 导出选中包为纯文本（File → Export Packet Dissections → As Plain Text）
```

---

## 三、二进制帧格式识别方法

### 3.1 识别消息头（Header）

```
方法 1: 找固定 Magic 字节
  - 在 Hex 视图中，每包前 4 字节通常是固定的
  - 同花顺已知: FD FD FD FD (0xFDFDFDFD)
  - Wireshark 中搜索: tcp.payload contains fd:fd:fd:fd

方法 2: 找重复出现的固定字节
  - 对比连续 10 个包
  - 位置固定不变的字节 = 协议标识 / 消息类型 / 股票代码
  - 位置规律变化的字节 = 价格 / 成交量 / 时间戳

方法 3: 识别长度字段
  - Magic 之后 4 字节通常是 Length（小端序）
  - 例: 如果 Hex 是 FD FD FD FD 3C 00 00 00
    - FD FD FD FD = Magic
    - 3C 00 00 00 = Length = 0x0000003C = 60 字节（小端）
```

### 3.2 识别股票代码编码

```
方法 1: ASCII 裸编码（老版本最常见）
  - 600000 → 36 30 30 30 30 30（6 字节 ASCII）
  - 000001 → 30 30 30 30 30 31
  - sh600000 → 73 68 36 30 30 30 30 30（8 字节）
  - sz000001 → 73 7A 30 30 30 30 30 31

方法 2: 16 进制市场+代码
  - 上海: 10 00 + 代码（市场标识 0x10 = 上海）
  - 深圳: 00 00 + 代码（市场标识 0x00 = 深圳）

方法 3: 搜索技巧
  - Wireshark: Ctrl+F → "Find Packet" → "String" → 输入 "600000"
  - 或 Hex Value → 输入 "36:30:30:30:30:30"
```

### 3.3 判断序列化格式

```
【C 语言结构体】特征（老版本最常见）：
  - 定长对齐（4 字节或 8 字节对齐）
  - 价格字段通常是 int32（乘以 100 或 1000 存储）
  - 成交量字段通常是 int32 或 int64
  - 时间戳可能是 Unix time（4 字节）或 HHMMSS（3 字节）
  - 识别方法: 价格 10.50 元 → 搜索 0x041A (1050) 或类似

【TLV 格式】特征：
  - Type (1-2 字节) + Length (1-4 字节) + Value (变长)
  - 识别方法: 看到连续的 Tag 字节（如 0x01, 0x02, 0x03...）

【Protobuf】特征：
  - 字段以 Varint 编码
  - Tag = (field_number << 3) | wire_type
  - 识别方法: 字节高位为 1 表示还有后续字节
  - 老版本同花顺（v10.01.02）大概率不是 Protobuf
```

### 3.4 实战分析步骤

```
步骤 1: 打开 pcap，过滤 tcp.port == 9528 && tcp.len > 0
步骤 2: 选择一个数据包，查看 Hex 面板
步骤 3: 记录前 20 字节的 Hex
步骤 4: 选择下一个数据包，对比 Hex
步骤 5: 标记"固定字节"和"变化字节"
步骤 6: 对变化字节做十进制转换，看是否匹配当前价格

示例分析：
Packet 1: FD FD FD FD 1A 00 00 00 36 30 30 30 30 30 01 00 00 00 29 29 00 00 ...
Packet 2: FD FD FD FD 1A 00 00 00 36 30 30 30 30 30 01 00 00 00 30 29 00 00 ...
                                              ^^^^^^^^^^^^^ 固定 = 600000
                                                            ^^^^^^^^^^^^^^ 变化 = 价格

步骤 7: 验证价格编码
  - 如果股票现价 10.50，变化字节是 0x2929 (= 10537) 或 0x0429 (= 1065)
  - 10537 / 100 = 105.37? 不对
  - 10537 / 1000 = 10.537? 接近
  - 尝试除以不同倍数，看哪个匹配实时价格
```

---

## 四、独家逆向经验（已验证）

### 4.1 明文股票代码

```
在 Wireshark 中：
  Edit → Find Packet → "Byte Stream" 或 "String"
  搜索: 600000
  
如果搜到，说明该 TCP 连接传输的是包含 600000 的行情数据。
记录该连接的: 服务器 IP、端口、TCP Stream 编号。
```

### 4.2 结构体模式识别

```
连续抓 5-10 个刷新瞬间的包，做"对比差分"：
  - 位置 [0-3]: 固定 → Magic
  - 位置 [4-7]: 固定 → 长度
  - 位置 [8-13]: 固定 → 股票代码 (ASCII)
  - 位置 [14-15]: 固定 → 市场标识
  - 位置 [16-19]: 变化 → 最新价 (int32)
  - 位置 [20-23]: 变化 → 成交量 (int32)
  - 位置 [24-27]: 变化 → 时间戳
  - 位置 [28-...]: 变化 → 买卖盘数据（10档 Bid/Ask）

十档盘口数据结构（推测）：
  struct {
    char code[6];        // 股票代码
    char market;         // 市场
    int32_t last_price;  // 最新价（×100）
    int32_t bid_1;       // 买一价
    int32_t bid_vol_1;   // 买一量
    int32_t ask_1;       // 卖一价
    int32_t ask_vol_1;   // 卖一量
    ...                  // 买二~买十，卖二~卖十
  };
```

### 4.3 价格编码验证

```
假设抓到以下变化字节：
  Packet 1: 价格字段 = 0x0000290D = 10509
  Packet 2: 价格字段 = 0x00002916 = 10518

如果当前实际价格约 10.51 元：
  10509 / 100 = 10.509 ✓
  10518 / 100 = 10.518 ✓

结论: 价格以"分"为单位存储（×100）
```

---

## 五、Wireshark 安装

如果尚未安装：
  1. 访问 https://www.wireshark.org/download.html
  2. 下载 Windows x64 Installer
  3. 安装时勾选 "Install Npcap"（抓包驱动）
  4. 重启电脑

---

## 六、预期成果

抓包成功后，你应该能看到：

| 字段 | 偏移（推测） | 编码 | 示例 |
|------|-------------|------|------|
| Magic | [0-3] | 固定 4 字节 | FD FD FD FD |
| Length | [4-7] | uint32 小端 | 3C 00 00 00 (=60) |
| 股票代码 | [8-13] | ASCII | 36 30 30 30 30 30 (=600000) |
| 市场 | [14] | 1 字节 | 01=上海, 00=深圳 |
| 最新价 | [15-18] | int32 | 价格 × 100 |
| 成交量 | [19-22] | int32 | 手数 |
| 买一价 | [23-26] | int32 | 价格 × 100 |
| 买一量 | [27-30] | int32 | 手数 |
| 卖一价 | [31-34] | int32 | 价格 × 100 |
| 卖一量 | [35-38] | int32 | 手数 |
| ... | ... | ... | 买二~买十, 卖二~卖十 |

---

## 七、下一步

抓到 pcap 后：
  1. 打开 Wireshark，分析帧格式
  2. 确认各字段含义
  3. 编写 Python 解析脚本，实现私有协议客户端
  4. 直连行情服务器，获取 L2 数据流
