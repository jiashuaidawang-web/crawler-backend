ths/
├── README.md              ← 总览 (两套方案对比)
├── crawler/               ← HTTP API 拦截方案 (已有)
│   ├── L2_DATA_CONTRACT.md
│   ├── l2_consumer.py
│   └── ...
├── tcp_l2/                ← TCP 私有协议方案 (新建)
│   ├── README.md          ← TCP 方案入口
│   ├── SETUP.md           ← 环境搭建 + 抓包 + 逆向 + 踩坑
│   ├── REVERSE_ENGINEERING.md ← 协议逆向全过程
│   ├── ARCHITECTURE.md    ← 架构 + 容量 + 优化方向
│   ├── ENVIRONMENT.md     ← 环境版本
│   ├── REDIS_DATA_FORMAT.md ← 消费端对接
│   ├── CONTRACT_STATUS.md ← 契约状态
│   ├── docker-compose.yml
│   ├── requirements.txt
│   ├── start.bat / status.bat / stop.bat
│   └── src/
│       ├── ths_l2_realtime.py
│       ├── ths_l2_push.py
│       └── ths_frames.py
├── docs/                  ← 项目文档
│   ├── 同花顺API文档.md
│   ├── hexin-v破解思路.md
│   └── 环境搭建指南.md
└── success/               ← 历史成功记录
└── step3_tcp_sniffing.md

# 同花顺 L2 实时数据系统

> 两种采集方案：HTTP API 拦截 / TCP 私有协议直连

---

## 方案对比

| 维度 | HTTP API 拦截 (`crawler/`) | TCP 私有协议 (`tcp_l2/`) |
|---|---|---|
| **抓包工具** | mitmproxy | Wireshark |
| **协议** | HTTP/HTTPS + JSON | TCP 私有二进制协议 |
| **服务器** | 同花顺 HTTP API | 139.159.194.69:9528 |
| **数据格式** | 结构化 JSON | 二进制帧 (JiTu/tb1.0/itb3.0) |
| **数据质量** | 完整 (价格/量/十档) | 部分 (JiTu 无价格，itb3.0 未解) |
| **Redis Key** | `ths_l2_realtime` (Stream) | `ths:l2:tick:{code}` / `ths:l2:quote:{code}` |
| **反爬难度** | 需绕过 SSL Pinning | 无 (纯 TCP) |
| **稳定性** | 依赖 API 不变 | 依赖协议不变 |

---

## 方案一：HTTP API 拦截 (`crawler/`)

> **原理**：用 mitmproxy 作为中间人代理，拦截同花顺 APP 的 HTTP API 调用

### 数据契约
详见 [crawler/L2_DATA_CONTRACT.md](crawler/L2_DATA_CONTRACT.md)

### 支持的数据类型
| 类型 | 说明 | 状态 |
|---|---|---|
| `snapshot` | 实时行情快照 (最新价/涨跌/市值等) | ✅ |
| `depth` | L2 十档盘口 (买十卖十价格+量) | ✅ |
| `tick` | L2 逐笔成交 (价格/量/方向) | ✅ |
| `trend` | 分时走势 | ✅ |

### 消费端
```bash
python crawler/l2_consumer.py --type tick --stock 000001
```

---

## 方案二：TCP 私有协议 (`tcp_l2/`)

> **原理**：直接用 TCP 连接同花顺 L2 服务器，接收推送的二进制帧并解码

### 数据契约
详见 [tcp_l2/REDIS_DATA_FORMAT.md](tcp_l2/REDIS_DATA_FORMAT.md)

### 支持的数据类型
| 类型 | 说明 | 状态 |
|---|---|---|
| `JiTu` | 逐笔成交 (方向+时间) | ✅ 已解 |
| `tb1.0` | 价格快照 (价格字符串) | ⚠️ 字段含义待确认 |
| `itb3.0` | 十档盘口 | ❌ 未破解 |

### 消费端
```bash
# 读取 tick
redis-cli XRANGE ths:l2:tick:002384 - +

# 读取 quote
redis-cli HGETALL ths:l2:quote:002384
```

---

## 快速开始

### HTTP API 方案
```bash
# 见 crawler/ 目录下的文档
```

### TCP 协议方案
```bash
cd tcp_l2

# 1. 启动 Redis
docker-compose up -d

# 2. 安装依赖
pip install -r requirements.txt

# 3. 设置股票池
redis-cli SADD ths:l2:pool 002384 600519 000001 300750 600667

# 4. 启动推送
python src/ths_l2_push.py
```

详见 [tcp_l2/SETUP.md](tcp_l2/SETUP.md)

---

## 核心文档

### TCP 协议方案 (tcp_l2/)
| 文档 | 内容 |
|---|---|
| [SETUP.md](tcp_l2/SETUP.md) | 环境搭建 + 抓包 + 逆向 + 踩坑 (20分钟) |
| [REVERSE_ENGINEERING.md](tcp_l2/REVERSE_ENGINEERING.md) | 协议逆向全过程 |
| [ARCHITECTURE.md](tcp_l2/ARCHITECTURE.md) | 架构设计 + 容量评估 + 优化方向 |
| [ENVIRONMENT.md](tcp_l2/ENVIRONMENT.md) | 环境版本详情 |
| [REDIS_DATA_FORMAT.md](tcp_l2/REDIS_DATA_FORMAT.md) | Redis 数据格式 (消费端对接) |
| [CONTRACT_STATUS.md](tcp_l2/CONTRACT_STATUS.md) | 数据契约状态 |

### HTTP API 方案 (crawler/)
| 文档 | 内容 |
|---|---|
| [L2_DATA_CONTRACT.md](crawler/L2_DATA_CONTRACT.md) | HTTP 方案的完整数据契约 |

---

## 环境要求

### 抓包环境 (模拟器)

| 组件 | 版本 | 说明 |
|---|---|---|
| 模拟器 | **雷电模拟器 9** | Android 模拟器 |
| Android | **Android 9 (API 28)** | 雷电中创建 Android 9 实例 |
| 同花顺 | **v10.01.02** | 2020 年老版本，无加固壳 |
| ROOT | 雷电设置中开启 | 用于写入系统证书 |

### 采集端

| 组件 | 版本 |
|---|---|
| Python | 3.11+ |
| Redis | 7.x (Docker) |
| Docker Desktop | 最新版 |
| Wireshark | 3.6+ (TCP 方案抓包) |
| mitmproxy | 9+ (HTTP 方案抓包) |

---

## 下一步

- [ ] 开盘后真实数据验证 (两个方案对比)
- [ ] TCP 方案：对照客户端确认 tb1.0 字段含义
- [ ] TCP 方案：破解 itb3.0 十档盘口
- [ ] 毫秒级延迟优化 (5ms 写入)
