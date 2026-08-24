#!/usr/bin/env python3
"""
同花顺 L2 实时数据推送 - 自动检测 pool 变化版本
- 持续运行，往 Redis 写真实数据
- 每 60 秒检查 pool，自动订阅新增股票
- 断线自动重连

使用方法:
  python ths_l2_push.py              # 启动
"""

import sys
import asyncio
import logging
import time
import json
from datetime import datetime

sys.stdout.reconfigure(encoding='utf-8')
logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s [%(levelname)s] %(message)s",
    datefmt="%H:%M:%S",
)

from ths_l2_realtime import THSProtocolParser, L2DataExtractor, RedisWriter, Config


# ======================= 配置 ========================
POOL_CHECK_INTERVAL = 60       # pool 检测间隔(秒)
REPORT_INTERVAL = 30           # 统计报告间隔(秒)
RECONNECT_INTERVAL = 5         # 重连间隔(秒)
# =====================================================


async def push_to_redis():
    """正式数据推送 - 持续运行，自动检测 pool 变化"""

    config = Config()
    writer = RedisWriter(config)
    await writer.connect()

    parser = THSProtocolParser()
    extractor = L2DataExtractor()

    # 当前已订阅的股票集合
    subscribed_stocks: set = set()
    writer_tcp = None

    async def refresh_pool():
        """从 Redis pool 读取最新股票列表，返回 set"""
        pool = await writer.redis.smembers("ths:l2:pool")
        return set(m.decode() if isinstance(m, bytes) else m for m in pool)

    async def subscribe_stock(code: str):
        """订阅单只股票"""
        nonlocal writer_tcp
        try:
            sub = parser.build_subscribe_frame(code)
            writer_tcp.write(sub)
            await writer_tcp.drain()
            subscribed_stocks.add(code)
            logging.info("[+] 订阅 %s (共 %d 支)" % (code, len(subscribed_stocks)))
            await asyncio.sleep(0.05)
        except Exception as e:
            logging.error("订阅 %s 失败: %s" % (code, e))

    async def subscribe_all(pool: set):
        """订阅 pool 中的所有股票"""
        for code in sorted(pool):
            if code not in subscribed_stocks:
                await subscribe_stock(code)

    # 写入 meta
    await writer.redis.hset("ths:l2:meta", mapping={
        "server": config.SERVER_HOST,
        "port": str(config.SERVER_PORT),
        "start_time": datetime.now().strftime("%Y-%m-%d %H:%M:%S"),
        "pool_check_interval": str(POOL_CHECK_INTERVAL),
        "status": "running",
    })

    logging.info("=" * 60)
    logging.info("同花顺 L2 实时数据推送启动")
    logging.info("Pool 检测间隔: %d 秒" % POOL_CHECK_INTERVAL)
    logging.info("=" * 60)

    reconnect_count = 0

    while True:
        try:
            # ========== 连接服务器 ==========
            logging.info("连接服务器 %s:%d ..." % (config.SERVER_HOST, config.SERVER_PORT))
            reader, writer_tcp = await asyncio.wait_for(
                asyncio.open_connection(config.SERVER_HOST, config.SERVER_PORT),
                timeout=10
            )
            logging.info("[OK] TCP 连接成功")
            reconnect_count = 0
            subscribed_stocks.clear()

            # ========== 登录 ==========
            login = parser.build_login_frame()
            writer_tcp.write(login)
            await writer_tcp.drain()
            logging.info("[OK] 已发送登录帧")
            await asyncio.sleep(1)

            # ========== 订阅 pool 中所有股票 ==========
            pool = await refresh_pool()
            if pool:
                logging.info("[OK] 股票池: %s" % sorted(pool))
                await subscribe_all(pool)
            else:
                logging.warning("[!] 股票池为空，等待添加...")

            # ========== 读取循环 ==========
            total_frames = 0
            total_ticks = 0
            total_tb10 = 0
            total_quotes = 0
            total_bytes = 0
            start_time = time.time()
            last_report = start_time
            last_pool_check = start_time

            while True:
                # 读取数据 (用 wait_for 实现超时，方便定期检查)
                try:
                    data = await asyncio.wait_for(reader.read(65536), timeout=1.0)
                except asyncio.TimeoutError:
                    # 超时不是断线，继续循环做定期检查
                    data = None

                if data is None:
                    # 超时，跳过处理，后面做定期任务
                    frames = []
                elif not data:
                    # 真正的断线: reader 返回空 bytes
                    logging.warning("服务器断开")
                    break
                else:
                    # 正常数据
                    pass

                if data is not None:
                    total_bytes += len(data)
                    parser.feed(data)
                    frames = parser.parse_frames()

                for frame in frames:
                    total_frames += 1

                    stock_code = extractor.extract_stock_code(frame)

                    # tick
                    ticks = extractor.extract_ticks(frame)
                    for tick in ticks:
                        code = tick.stock_code or stock_code or "unknown"
                        await writer.add_tick(code, tick)
                        total_ticks += 1

                    # quote
                    quote = extractor.extract_quote(frame)
                    if quote:
                        code = stock_code or "unknown"
                        await writer.add_quote(code, quote)
                        total_quotes += 1

                    # tb10
                    tb10 = extractor.parse_tb10(frame)
                    if tb10:
                        code = tb10["stock_code"] or stock_code or "unknown"
                        await writer.add_tb10(code, tb10)
                        total_tb10 += 1

                    # events
                    json_data = frame.get("json")
                    if json_data and "events" in json_data:
                        events = json_data["events"]
                        for evt in events:
                            evt["_recv_time"] = datetime.now().strftime("%Y-%m-%d %H:%M:%S")
                            await writer.redis.xadd("ths:l2:events", {
                                "data": json.dumps(evt, ensure_ascii=False)
                            })

                # 定期刷新 Redis
                await writer.flush_all()

                now = time.time()

                # ========== 定期检查 pool 变化 ==========
                if now - last_pool_check >= POOL_CHECK_INTERVAL:
                    last_pool_check = now
                    pool = await refresh_pool()
                    new_stocks = pool - subscribed_stocks
                    if new_stocks:
                        logging.info("[!] 发现 %d 支新股票: %s" % (
                            len(new_stocks), sorted(new_stocks)))
                        for code in sorted(new_stocks):
                            await subscribe_stock(code)
                        # 更新 meta
                        await writer.redis.hset("ths:l2:meta", mapping={
                            "subscribed": ",".join(sorted(subscribed_stocks)),
                            "subscribed_count": str(len(subscribed_stocks)),
                        })

                # ========== 定期报告 ==========
                if now - last_report >= REPORT_INTERVAL:
                    elapsed = int(now - start_time)
                    fps = total_frames / elapsed if elapsed > 0 else 0
                    logging.info(
                        "📊 %ds | 帧=%d | ticks=%d | quotes=%d | tb10=%d | %.1f帧/s | %dKB | 订阅=%d" % (
                            elapsed, total_frames, total_ticks, total_quotes, total_tb10,
                            fps, total_bytes // 1024, len(subscribed_stocks)
                        )
                    )
                    await writer.redis.hset("ths:l2:meta", mapping={
                        "last_update": datetime.now().strftime("%Y-%m-%d %H:%M:%S"),
                        "total_frames": str(total_frames),
                        "total_ticks": str(total_ticks),
                        "total_tb10": str(total_tb10),
                        "total_quotes": str(total_quotes),
                        "total_kb": str(total_bytes // 1024),
                        "subscribed_count": str(len(subscribed_stocks)),
                        "subscribed": ",".join(sorted(subscribed_stocks)),
                    })
                    last_report = now

        except ConnectionRefusedError:
            logging.error("连接被拒绝")
            reconnect_count += 1
        except ConnectionResetError:
            logging.error("连接被重置")
            reconnect_count += 1
        except asyncio.CancelledError:
            break
        except Exception as e:
            logging.error("错误: %s" % e, exc_info=True)
            reconnect_count += 1

        if reconnect_count > 10:
            logging.error("重连次数过多，停止")
            break

        logging.info("%d秒后重连..." % RECONNECT_INTERVAL)
        await asyncio.sleep(RECONNECT_INTERVAL)

    # 更新状态
    try:
        await writer.redis.hset("ths:l2:meta", {"status": "stopped"})
    except:
        pass


if __name__ == "__main__":
    asyncio.run(push_to_redis())
