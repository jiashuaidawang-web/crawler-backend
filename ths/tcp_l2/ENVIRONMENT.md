# 环境版本详情

> 记录当前运行环境的所有版本信息，确保可复现

## 抓包环境 (模拟器)

> TCP 协议的帧是从模拟器抓包提取的，模拟器的版本直接影响是否能复现

| 项目 | 版本 | 说明 |
|---|---|---|
| 模拟器 | **雷电模拟器 9** | Android 模拟器 |
| Android 版本 | **Android 9 (API 28)** | 雷电中创建 Android 9 实例 |
| 同花顺版本 | **v10.01.02** | 2020 年老版本，无加固壳 |
| ROOT 权限 | 雷电设置中开启 | 用于写入 mitmproxy 系统证书 |
| mitmproxy | 11.0.2 | HTTPS 抓包 |

**为什么选 v10.01.02**：
- 无加固壳 (没有 libhssl-2.1.so、libjiagu.so 等对抗)
- HTTPS 可被 mitmproxy 解密
- L2 数据走私有 TCP 协议 (端口 9528)，不走 HTTPS

**雷电模拟器 9 下载**：https://www.ldmnq.com/

**同花顺 v10.01.02 APK**：需自行寻找 (应用宝历史版本或第三方市场)

## 采集端 (Windows)

| 项目 | 版本 |
|---|---|
| OS | Windows 10 Enterprise |
| Version | 10.0.19045 |

## Python

| 项目 | 版本 |
|---|---|
| Python | 3.11.9 |
| pip | 26.2.1 |

### Python 依赖包

| 包名 | 版本 | 用途 |
|---|---|---|
| redis | 8.1.0 | Redis 客户端 (含 async 支持) |
| scapy | 2.7.0 | pcap 文件解析/重放 |
| aioredis | 2.0.1 | 异步 Redis (备用) |
| websockets | 13.1 | WebSocket 支持 |

安装命令：
```bash
pip install redis scapy
```

## Redis

| 项目 | 版本 |
|---|---|
| Redis Server | 7.4.11 |
| 镜像 | redis:7-alpine |
| 容器名 | redis-l2 |
| 端口 | 6379 |
| 持久化 | appendonly yes |

启动方式：
```bash
docker run -d --name redis-l2 -p 6379:6379 redis:7-alpine redis-server --appendonly yes
```

或使用 docker-compose：
```bash
docker-compose up -d
```

## Docker

| 项目 | 版本 |
|---|---|
| Docker Desktop | (当前安装版本) |
| Docker Engine | (对应版本) |

## 同花顺服务器

| 项目 | 值 |
|---|---|
| 服务器 IP | 139.159.194.69 |
| 端口 | 9528 |
| 协议 | TCP 私有协议 |
| Magic | 0xFDFDFDFD |

## 关键配置参数

### Config (ths_l2_realtime.py)

```python
class Config:
    SERVER_HOST = "139.159.194.69"    # 同花顺服务器
    SERVER_PORT = 9528                 # 端口
    REDIS_HOST = "127.0.0.1"          # Redis 地址
    REDIS_PORT = 6379                  # Redis 端口
    REDIS_DB = 0                       # Redis 数据库
    FLUSH_INTERVAL_MS = 100            # 刷新间隔 (ms)
    RECONNECT_INTERVAL = 5             # 重连间隔 (s)
    TICK_MAX_LEN = 10000               # 每支股票 tick 流最大长度
    QUOTE_TTL = 60                     # quote 过期时间 (s)
```

### 推送配置 (ths_l2_push.py)

```python
POOL_CHECK_INTERVAL = 60    # pool 检测间隔 (秒)
REPORT_INTERVAL = 30        # 统计报告间隔 (秒)
RECONNECT_INTERVAL = 5      # 重连间隔 (秒)
```

## 目录结构

```
D:/stock/data/
├── ths/                         # 项目文档和配置
│   ├── README.md
│   ├── SETUP.md
│   ├── ARCHITECTURE.md
│   ├── ENVIRONMENT.md
│   ├── REDIS_DATA_FORMAT.md
│   ├── CONTRACT_STATUS.md
│   ├── docker-compose.yml
│   └── requirements.txt
├── ths_l2_realtime.py           # 核心库
├── ths_l2_push.py               # 推送主程序
├── restart.pcapng               # pcap 抓包文件
└── (其他测试脚本)
```
