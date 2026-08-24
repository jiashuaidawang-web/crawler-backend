# 同花顺 L2 协议逆向全过程

> 从抓包到破解协议的完整方法论
> 目标：让后来者能复现整个破解流程

---

## 环境版本

> 以下版本是实际抓包环境的版本，复现时需保持一致

| 组件 | 版本 | 说明 |
|---|---|---|
| 模拟器 | **雷电模拟器 9** | Android 模拟器 |
| Android | **Android 9 (API 28)** | 雷电中创建 Android 9 实例 |
| 同花顺 | **v10.01.02** | 2020 年老版本，无加固壳 |
| ROOT | 雷电设置中开启 | 用于写入系统证书 |
| mitmproxy | 11.0.2 | HTTPS 抓包 |
| Wireshark | 3.6+ | TCP 抓包 |
| Python | 3.11.9 | 解析/重放脚本 |
| scapy | 2.7.0 | pcap 解析 |

**为什么选 v10.01.02**：无加固壳，HTTPS 可解密，L2 走私有 TCP

---

## 总览

```
 Step 1    Step 2    Step 3    Step 4    Step 5
┌────────┐┌────────┐┌────────┐┌────────┐┌────────┐
│  抓包   │→│ 定位帧  │→│ 提取模板│→│ 连接测试│→│ 解码数据│
│Wireshark││Magic   ││C->S帧  ││TCP重放 ││JiTu/tb1│
└────────┘└────────┘└────────┘└────────┘└────────┘
```

---

## Step 1: 抓包 — 捕获原始流量

### 工具
- **Wireshark** + Npcap
- 过滤条件：`tcp.port == 9528`

### 过程
1. 启动 Wireshark，选择上网网卡
2. 设置过滤器 `tcp.port == 9528`
3. 打开同花顺客户端 → 登录 → 打开某股票 L2 页面
4. 停止抓包，保存为 `ths.pcapng`

### 关键发现
| 发现 | 值 |
|---|---|
| 服务器 IP | 139.159.194.69 |
| 服务器端口 | 9528 |
| 协议 | TCP 私有协议 |
| 方向识别 | `dport==9528` 是 C→S，`sport==9528` 是 S→C |

---

## Step 2: 定位帧 — 找出帧边界

### 分析方法
用 Python + scapy 读取 pcap，观察字节模式：

```python
from scapy.all import rdpcap, TCP, Raw

packets = rdpcap("ths.pcapng")
for pkt in packets:
    if pkt.haslayer(TCP) and pkt.haslayer(Raw):
        raw = pkt[Raw].load
        print(f"长度={len(raw)} 前8字节={raw[:8].hex()}")
```

### 发现：Magic 标记
```
所有帧的前 4 字节都是: FDFDFDFD
这就是帧的起始标记 (Magic Number)
```

### 帧结构初步分析
```
FDFDFDFD 3030303030326635 001C 00 00000000 00000007 0000FFD9 ...
├────────┤├──────────────┤├──┤├─┤├────────┤├────────┤
  Magic    MsgID(8字节)   类型  0  Seq     PayloadLen  ...
```

| 偏移 | 长度 | 含义 |
|---|---|---|
| 0-3 | 4 | Magic: `0xFDFDFDFD` |
| 4-11 | 8 | MsgID (ASCII) |
| 12-13 | 2 | Frame Type |
| 14 | 1 | 固定 0 |
| 15 | 1 | Seq (帧序号) |
| 16-19 | 4 | Payload Length |
| 20-35 | 16 | 保留字段 |
| 36-39 | 2 | 固定标记 `0xD502` |

### 帧类型
| Type | 方向 | 说明 |
|---|---|---|
| 0x001C | C→S | 客户端发给服务器 |
| 0x0018 | S→C | 服务器发给客户端 |

---

## Step 3: 提取模板 — 找出登录帧和订阅帧

### 方法
过滤 C→S 帧（`dport==9528`），搜索关键字：

```python
for pkt in packets:
    if tcp.dport == 9528 and pkt.haslayer(Raw):
        raw = pkt[Raw].load
        if b'signapp=' in raw:     # 登录帧特征
            print('LOGIN:', raw.hex())
        if b'stockcode=' in raw:   # 订阅帧特征
            print('SUBSCRIBE:', raw.hex())
```

### 提取结果
| 帧 | 特征 | 用途 |
|---|---|---|
| 登录帧 | 包含 `signapp=android` | 身份认证 |
| 订阅帧 | 包含 `stockcode=002384` | 订阅行情 |

### 订阅帧完整格式
```
[frame]
id=2205
pageList=10063,10064,10028,10066,10068,10056
reqPage=10056
reqPageCount=1
[10056]
id=1214

stockcode=002384
marketcode=33
```

**关键**：`stockcode` 可以替换为任意股票代码，实现订阅不同股票。

### 生成 ths_frames.py
将提取到的帧硬编码为 Python bytes，作为后续连接的模板。

---

## Step 4: 连接测试 — TCP 重放

### 方法
1. 连接服务器 `139.159.194.69:9528`
2. 发送登录帧（原始字节）
3. 等 1 秒
4. 发送订阅帧（替换 stockcode）
5. 循环读取响应

```python
reader, writer = await asyncio.open_connection("139.159.194.69", 9528)

# 登录
writer.write(LOGIN_FRAME)
await writer.drain()
await asyncio.sleep(1)

# 订阅
sub_frame = SUBSCRIBE_FRAME.replace(b'stockcode=002384', b'stockcode=600519')
writer.write(sub_frame)
await writer.drain()

# 读取
while True:
    data = await reader.read(65536)
    print(f"收到 {len(data)} 字节")
```

### 验证
- ✅ 连接成功
- ✅ 收到服务器推送的心跳 (每 60 秒)
- ⚠️ 交易时间外只有心跳，无行情数据

---

## Step 5: 解码数据 — 解析 S→C 帧

### 5.1 帧切分
```python
def parse_frames(data):
    frames = []
    while True:
        pos = data.find(b'\xfd\xfd\xfd\xfd')  # 找 Magic
        if pos == -1:
            break
        # 解析 header 获取 payload 长度
        payload_len = struct.unpack_from('<I', data, 16)[0]
        frame_len = HEADER_SIZE + payload_len
        frames.append(data[pos:pos+frame_len])
        data = data[pos+frame_len:]
    return frames
```

### 5.2 帧头变长问题 (关键坑)
**现象**：JSON 帧和 binary 帧的 header 长度不同

```
JSON 帧:   header = 37 字节, payload 从偏移 37 开始
binary帧:  header = 40 字节, payload 从偏移 40 开始
```

**识别方法**：
```python
if data[37:39] == b'{":     # JSON 帧
    payload_start = 37
elif data.find(b'tb1.0', 36, 44) != -1:  # binary 帧
    payload_start = 40
```

### 5.3 JiTu (逐笔成交) 解码

**格式**：JSON
```json
{
  "JiTu": [{
    "stockcode": "002384",
    "value": "[{\"signal\":\"Tu\",\"time\":605},{\"signal\":\"Ji\",\"time\":608}]"
  }]
}
```

**字段**：
| 字段 | 值 | 含义 |
|---|---|---|
| signal | `Tu` | 卖出 |
| signal | `Ji` | 买入 |
| time | 605 | 毫秒 (从 0 点起算) |

**解析**：
```python
data = json.loads(payload)
for item in data.get("JiTu", []):
    code = item["stockcode"]
    ticks = json.loads(item["value"])
    for t in ticks:
        signal = t["signal"]  # "Tu" or "Ji"
        time_ms = t["time"]   # 毫秒
```

### 5.4 tb1.0 (价格快照) 解码

**格式**：二进制，UTF-16LE 编码

**Header (40 bytes)**：
```
[0:6]   = "tb1.0\x00"
[6:10]  = count (DWORD)
[10:14] = reserved
[14:18] = reserved
[18:20] = struct_size (WORD)
[20:22] = market (WORD)
[22:26] = flags (DWORD)
[26:30] = unknown1 (DWORD)
[30:34] = timestamp1 (DWORD)
[34:36] = unknown2 (WORD)
[36:40] = timestamp2 (DWORD)
```

**Data Area**：UTF-16LE 编码的字符串
- 股票代码：`002384` → `300030003200330038003400`
- 价格：`201.19` → `3200300031002e0031003900`

**解析**：
```python
# 提取 UTF-16LE 字符串
i = 0
while i < len(data_area) - 1:
    if data_area[i+1] == 0 and 0x20 <= data_area[i] < 0x7f:
        chars = []
        while i < len(data_area) - 1 and data_area[i+1] == 0:
            chars.append(chr(data_area[i]))
            i += 2
        s = ''.join(chars)
        # s 就是 "002384" 或 "201.19"
    else:
        i += 1
```

### 5.5 itb3.0 (十档盘口) — 未完全破解

**已知**：
- Header 40 字节
- 数据区有 `0x24` 标记每 4 字节重复
- 可能格式：`0x24` + 3 字节价格数据

**未知**：
- 价格编码方式（/100, /1000, /10000 都不对）
- 买/卖盘分离方式
- 挂单量编码

---

## 踩坑汇总

| # | 坑 | 影响 | 解法 |
|---|---|---|---|
| 1 | 帧头变长 (37 vs 40) | JSON 截断，tick 全丢 | 动态检测 payload 偏移 |
| 2 | asyncio 超时误判断线 | 每秒"断线"重连 | 用 None 区分超时和断线 |
| 3 | 运行目录错误 | ModuleNotFoundError | cd 到正确目录 |
| 4 | Python heredoc 用 `//` | 语法错误 | 用 `#` |
| 5 | 周日无行情 | ticks 始终 0 | 等交易日 |
| 6 | Redis close() 弃用 | 警告 | 改用 aclose() |

---

## 方法论总结

### 协议逆向通用流程

```
1. 抓包 → 定位目标流量 (ip:port 过滤)
2. 找帧头 → 找固定标记 (Magic/Header)
3. 分类帧 → 按类型/方向分组
4. 提取模板 → 找到可重放的 C→S 帧
5. 连接测试 → TCP 重放验证
6. 解码数据 → 按格式解析 S→C 帧
7. 验证对照 → 对比客户端显示确认
```

### 关键技巧

| 技巧 | 说明 |
|---|---|
| Magic 定位 | 几乎所有私有协议都有固定帧头 |
| 方向识别 | `dport==X` 是请求，`sport==X` 是响应 |
| 关键字搜索 | `signapp=`, `stockcode=` 等特征字符串 |
| 对比法 | 同一股票多次抓包，看哪些字节变化 |
| UTF-16LE | 中文/数字编码常用，特征是每隔一个字节为 0x00 |
| 重放验证 | 用原始字节重放，确认服务器响应 |

---

## 已知未知 (待破解)

| 数据格式 | 状态 | 下一步 |
|---|---|---|
| JiTu | ✅ 已解 | 只有方向+时间，无价格/量 |
| tb1.0 | ⚠️ 半解 | 价格可解，字段含义待对照 |
| itb3.0 | ❌ 未解 | 需要对照客户端十档数据 |
