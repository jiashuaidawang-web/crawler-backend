# 环境搭建指南

> 目标：新机器 20 分钟内完成环境搭建并跑通数据流
> 包含：环境安装 + 抓包方法 + 协议逆向 + 所有踩坑

---

## 第一部分：环境安装 (10 分钟)

### 1.1 安装 Python (3 分钟)

下载并安装 Python 3.11+：
https://www.python.org/downloads/

安装时 **勾选 "Add Python to PATH"**

验证：
```bash
python --version
# Python 3.11.x
```

### 1.2 安装 Docker Desktop (5 分钟，如已安装跳过)

下载 Docker Desktop for Windows：
https://www.docker.com/products/docker-desktop/

安装后启动 Docker，等待状态栏显示 "Docker Desktop is running"

验证：
```bash
docker --version
```

### 1.3 启动 Redis (2 分钟)

在 `ths/` 目录下执行：

```bash
docker-compose up -d
```

验证：
```bash
docker ps
redis-cli ping
# PONG
```

### 1.4 安装 Python 依赖 (1 分钟)

```bash
pip install -r requirements.txt
```

---

## 第二部分：抓包 — 获取同花顺流量 (5 分钟)

> ⚠️ **核心前提**：需要获取同花顺客户端的登录帧和订阅帧，才能与服务器通信

### 版本说明

| 组件 | 版本 | 说明 |
|---|---|---|
| 模拟器 | **雷电模拟器 9** | Android 模拟器 |
| Android | **Android 9 (API 28)** | 雷电中创建 Android 9 实例 |
| 同花顺 | **v10.01.02** | 2020 年老版本，无加固壳 |
| ROOT | 雷电设置中开启 | 用于抓包时解密 |
| mitmproxy | 11.0.2 | HTTPS 抓包 (如用 HTTP 方案) |

**为什么选 v10.01.02**：
- 无加固壳 (没有 libhssl-2.1.so、libjiagu.so 等对抗)
- HTTPS 可被 mitmproxy 解密
- L2 数据走私有 TCP 协议 (端口 9528)，不走 HTTPS

### 2.1 安装 Wireshark (3 分钟)

下载：https://www.wireshark.org/download.html

安装时勾选：
- ✅ Npcap (抓包驱动)
- ✅ WinPcap 兼容模式

### 2.2 抓包步骤

1. **启动 Wireshark**
2. **选择网卡**：选择正在上网的网卡（通常是 "WLAN" 或 "以太网"）
3. **设置过滤器**：在过滤栏输入 `tcp.port == 9528`
4. **开始抓包**：点击蓝色鲨鱼鳍按钮
5. **操作同花顺客户端**：
   - 打开同花顺 APP/PC
   - 登录账号
   - 打开任意股票的 L2 行情页面
6. **停止抓包**：点击红色方块
7. **保存**：`File → Save As` → 保存为 `ths.pcapng`

### 2.3 从 pcap 提取关键帧

运行提取脚本：

```bash
cd src
python -c "
from scapy.all import rdpcap, TCP, Raw
packets = rdpcap('../restart.pcapng')

# 提取所有发往 9528 的帧 (C->S)
for pkt in packets:
    if pkt.haslayer(TCP) and pkt.haslayer(IP):
        tcp = pkt[TCP]
        if tcp.dport == 9528 and pkt.haslayer(Raw):
            raw = pkt[Raw].load
            # 查找登录帧 (包含 'signapp=android')
            if b'signapp=' in raw:
                print('=== LOGIN FRAME ===')
                print(raw.hex())
            # 查找订阅帧 (包含 'stockcode=')
            if b'stockcode=' in raw:
                print('=== SUBSCRIBE FRAME ===')
                print(raw.hex())
"
```

### 2.4 生成 ths_frames.py

将提取到的帧填入 `src/ths_frames.py`：

```python
# 登录帧 (从 pcap 提取的 C->S 帧完整字节)
LOGIN_FRAME = bytes.fromhex("...登录帧hex...")

# 订阅帧 (包含 stockcode=002384)
SUBSCRIBE_FRAME = bytes.fromhex("...订阅帧hex...")
```

**当前已提取的帧**在 `src/ths_frames.py` 中，新机器可以直接用。

---

## 第三部分：协议逆向 — 踩坑记录

> 以下是逆向同花顺协议时踩过的坑，新环境可能遇到

### 坑 1：帧头是变长的 (37 vs 40 字节)

**现象**：JSON 帧和 binary 帧的帧头长度不同
- JSON 帧：帧头 **37** 字节
- itb3.0/tb1.0 帧：帧头 **40** 字节

**错误做法**：假设固定 40 字节偏移提取 JSON

**后果**：JSON 被截断 3 字节，`{"JiTu"` 变成 `iTu"`，所有 tick 数据丢失

**修复**：通过查找已知标记（`{`, `itb3.0`, `tb1.0`）动态确定 payload 起始位置

```python
def _find_payload_offset(self, data: bytes) -> int:
    if data[37:39] == b'{":     # JSON 帧
        return 37
    # 查找 binary 标记
    for marker in [b'itb3.0', b'tb1.0', b'ltb1.0']:
        pos = data.find(marker, 36, 44)
        if pos != -1:
            return pos
    return 37
```

### 坑 2：服务器 IP 和端口

**同花顺 L2 服务器**：
- IP: `139.159.194.69`
- Port: `9528`

**如何确认**：在 Wireshark 中过滤 `tcp.port == 9528`，看同花顺客户端连接的远程 IP

### 坑 3：订阅帧格式

**正确格式**：
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

**踩坑**：
- `pageList` 必须包含 `10056` (L2 十档)
- `marketcode=33` 表示深圳市场，`11` 表示上海市场
- 帧的 Magic 是 `0xFDFDFDFD`

### 坑 4：Python heredoc 中的注释

**错误**：在 bash heredoc 中使用 `//` 注释
```bash
python << 'PYEOF'
// 这是错的，Python 不认识 //
x = 1
PYEOF
```

**正确**：使用 `#`
```bash
python << 'PYEOF'
# 这才是对的
x = 1
PYEOF
```

### 坑 5：运行目录问题

**错误**：在项目根目录运行 `python ths_l2_push.py`，提示 `ModuleNotFoundError`

**原因**：`ths_l2_realtime.py` 在 `D:/stock/data/` 下

**正确**：
```bash
cd D:/stock/data && python ths_l2_push.py
```

### 坑 6：asyncio.wait_for 超时误判为断线

**错误代码**：
```python
try:
    data = await asyncio.wait_for(reader.read(65536), timeout=1.0)
except asyncio.TimeoutError:
    data = b''  # ← 空字节会被下面的判断当成断线

if not data:
    break  # ← 每次都"断线"
```

**修复**：用 `None` 表示超时
```python
try:
    data = await asyncio.wait_for(reader.read(65536), timeout=1.0)
except asyncio.TimeoutError:
    data = None  # ← 超时用 None 表示

if data is None:
    continue  # ← 继续循环
if not data:
    break  # ← 这才是真断线
```

### 坑 7：周日只有心跳

**现象**：连接成功但 `ticks=0`, `tb10=0`

**原因**：
- 交易时间：周一至周五 9:30-11:30, 13:00-15:00
- 周末/节假日只发心跳 (eventID="00000002")

**验证**：`redis-cli XLEN ths:l2:tick:002384` 返回 0 是正常的

### 坑 8：Redis close() 弃用警告

**警告**：`DeprecationWarning: Use aclose() instead of close()`

**当前**：`redis-py 8.1.0` 中 `close()` 仍可用但会警告

**修复**：改用 `aclose()` (async 版本)

---

## 第四部分：启动和验证 (3 分钟)

### 4.1 配置股票池

```bash
redis-cli SADD ths:l2:pool 002384 600519 000001 300750 600667
```

### 4.2 启动推送

```bash
cd src
python ths_l2_push.py
```

成功输出：
```
============================================================
同花顺 L2 实时数据推送启动
Pool 检测间隔: 60 秒
============================================================
连接服务器 139.159.194.69:9528 ...
[OK] TCP 连接成功
[OK] 已发送登录帧
[OK] 股票池: ['000001', '002384', '300750', '600519', '600667']
[+] 订阅 000001 (共 1 支)
[+] 订阅 002384 (共 2 支)
[+] 订阅 300750 (共 3 支)
[+] 订阅 600519 (共 4 支)
[+] 订阅 600667 (共 5 支)
📊 30s | 帧=9 | ticks=0 | quotes=0 | tb10=1 | 0.3帧/s | 1KB | 订阅=5
```

### 4.3 验证数据

```bash
# 元数据
redis-cli HGETALL ths:l2:meta

# 股票池
redis-cli SMEMBERS ths:l2:pool

# 事件流 (心跳)
redis-cli XRANGE ths:l2:events - + COUNT 3

# 推送任务状态
redis-cli HGET ths:l2:meta status
# 应返回 "running"
```

### 4.4 测试 pool 自动检测

```bash
# 添加一只新股票
redis-cli SADD ths:l2:pool 600036

# 等待 60 秒，看日志是否输出：
# [!] 发现 1 支新股票: ['600036']
# [+] 订阅 600036 (共 6 支)

# 清理测试
redis-cli SREM ths:l2:pool 600036
```

---

## 第五部分：新环境没有 pcap 怎么办？

> 如果没有同花顺客户端可以抓包，可以直接用已提取的帧

### 方案 A：直接复制 ths_frames.py (推荐)

`src/ths_frames.py` 已经包含：
- `LOGIN_FRAME`: 登录帧 (含账号密码)
- `SUBSCRIBE_FRAME`: 订阅帧 (含 stockcode=002384)

直接复制到新机器即可。

### 方案 B：自己抓包提取

1. 安装同花顺 APP (Android) 或 PC 版
2. 用 Wireshark 抓包
3. 过滤 `tcp.port == 9528`
4. 找包含 `signapp=` 的帧 → 登录帧
5. 找包含 `stockcode=` 的帧 → 订阅帧
6. 填入 `ths_frames.py`

### 方案 C：用测试脚本重放

```bash
# 从已有的 pcap 中提取 C->S 帧并重放
python ths_l2_test2.py
```

---

## 第六部分：常见问题排查

### Q: 连接服务器失败

```bash
# 1. 检查网络
ping 139.159.194.69

# 2. 检查端口
telnet 139.159.194.69 9528

# 3. 检查防火墙
# 确保 Windows 防火墙允许 Python 访问网络
```

### Q: ticks 始终为 0

- 周末/节假日正常（只有心跳）
- 交易时间：周一至周五 9:30-11:30, 13:00-15:00
- 检查 `redis-cli HGET ths:l2:meta total_frames` 是否有帧进来

### Q: Redis 连接失败

```bash
# 检查 Redis 是否运行
docker ps | findstr redis

# 检查端口
redis-cli ping

# 重启 Redis
docker-compose restart
```

### Q: 模块导入失败

```bash
# 确保在正确目录
cd D:/stock/data

# 检查文件存在
dir ths_l2_realtime.py
dir ths_l2_push.py
dir ths_frames.py
```

### Q: pool 变了但没有自动订阅

- 检查 `POOL_CHECK_INTERVAL` (默认 60 秒)
- 查看日志是否有 `[!] 发现 N 支新股票`
- 确认 pool 确实有变化：`redis-cli SMEMBERS ths:l2:pool`

---

## 完整搭建时间线

| 步骤 | 时间 | 累计 |
|---|---|---|
| 安装 Python | 3 min | 3 min |
| 安装 Docker | 5 min | 8 min |
| 启动 Redis | 2 min | 10 min |
| 安装依赖 | 1 min | 11 min |
| 复制代码+配置 | 1 min | 12 min |
| 设置 pool + 启动 | 2 min | 14 min |
| 验证 | 1 min | 15 min |

**已有环境的情况下（跳过 Python/Docker）：6 分钟搞定**

---

## 附录：一键脚本

Windows 下可以直接运行：

```bash
# 启动
ths\start.bat

# 查看状态
ths\status.bat

# 停止
ths\stop.bat
```
