# TCP 私有协议方案 — 同花顺 L2 实时数据

> 直接用 TCP 连接同花顺 L2 服务器 (139.159.194.69:9528)，接收推送的二进制帧并解码

## 快速开始

```bash
# 1. 启动 Redis
docker-compose up -d

# 2. 安装依赖
pip install -r requirements.txt

# 3. 设置股票池
redis-cli SADD ths:l2:pool 002384 600519 000001 300750 600667

# 4. 启动推送
cd src
python ths_l2_push.py
```

## 目录结构

```
tcp_l2/
├── README.md              # 本文件
├── SETUP.md               # 环境搭建 + 抓包 + 逆向 + 踩坑 (20分钟)
├── REVERSE_ENGINEERING.md # 协议逆向全过程
├── ARCHITECTURE.md        # 架构设计 + 容量评估
├── ENVIRONMENT.md         # 环境版本详情
├── REDIS_DATA_FORMAT.md   # Redis 数据格式 (消费端对接)
├── CONTRACT_STATUS.md     # 数据契约状态
├── docker-compose.yml     # Redis 容器配置
├── requirements.txt       # Python 依赖
├── start.bat              # 一键启动
├── status.bat             # 状态检查
├── stop.bat               # 停止
└── src/                   # 源码
    ├── ths_l2_realtime.py # 核心库 (Parser + Extractor + Writer)
    ├── ths_l2_push.py     # 推送主程序
    └── ths_frames.py      # 关键帧字节 (从 pcap 提取)
```

## 核心文档

| 文档 | 内容 | 阅读时间 |
|---|---|---|
| [SETUP.md](SETUP.md) | 环境搭建 + 抓包 + 逆向 + 所有踩坑 | 10 min |
| [REVERSE_ENGINEERING.md](REVERSE_ENGINEERING.md) | 协议逆向全过程 (方法论) | 15 min |
| [ARCHITECTURE.md](ARCHITECTURE.md) | 架构设计 + 容量评估 + 优化方向 | 10 min |
| [REDIS_DATA_FORMAT.md](REDIS_DATA_FORMAT.md) | Redis 数据格式 (消费端对接用) | 5 min |
| [CONTRACT_STATUS.md](CONTRACT_STATUS.md) | 数据契约状态 (已知/未知) | 5 min |
| [ENVIRONMENT.md](ENVIRONMENT.md) | 环境版本详情 | 3 min |

## 环境版本

| 组件 | 版本 | 说明 |
|---|---|---|
| 模拟器 | **雷电模拟器 9** | Android 模拟器 |
| Android | **Android 9 (API 28)** | 雷电中创建 Android 9 实例 |
| 同花顺 | **v10.01.02** | 2020 年老版本，无加固壳 |
| ROOT | 雷电设置中开启 | 用于写入系统证书 |
| mitmproxy | 11.0.2 | HTTPS 抓包 |

## 当前状态

| 组件 | 状态 |
|---|---|
| TCP 连接 | ✅ 已连接 139.159.194.69:9528 |
| Redis | ✅ Docker 运行中 |
| 数据推送 | ✅ 后台运行中 |
| 股票池自动检测 | ✅ 已验证 |
| JiTu 解析 | ✅ 方向+时间 |
| tb1.0 解析 | ⚠️ 价格可解，字段含义待确认 |
| itb3.0 解析 | ❌ 未解析 (十档盘口) |

## 数据流

```
同花顺服务器 ──TCP──▶ 解析器 ──结构化数据──▶ Redis ──▶ 消费端
139.159.194.69:9528      (Parser)         (Pipeline)     (你的业务)
                         (Extractor)
```

## 与 HTTP 方案对比

本项目还有另一套方案（HTTP API 拦截），见 `../crawler/`。

| 维度 | TCP 方案 (本目录) | HTTP 方案 (../crawler/) |
|---|---|---|
| 数据质量 | 部分未解 | 完整 |
| 反爬 | 无 | 需绕过 SSL Pinning |
| 稳定性 | 依赖协议不变 | 依赖 API 不变 |
