"""
同花顺 L2 实时数据消费端
从 Redis Stream 读取 L2 数据，支持按类型过滤

用法:
    python l2_consumer.py                  # 消费所有类型
    python l2_consumer.py --type depth     # 只消费十档盘口
    python l2_consumer.py --type tick      # 只消费逐笔成交
    python l2_consumer.py --type snapshot  # 只消费行情快照
    python l2_consumer.py --type trend     # 只消费分时走势
    python l2_consumer.py --stock 000001   # 只消费指定股票
    python l2_consumer.py --history 100    # 先读100条历史，再实时
"""
import json
import argparse
import sys
import signal
import redis


REDIS_HOST = "127.0.0.1"
REDIS_PORT = 6379
REDIS_STREAM_KEY = "ths_l2_realtime"


class L2Consumer:
    def __init__(self, data_type=None, stock_code=None):
        self.redis = redis.Redis(
            host=REDIS_HOST,
            port=REDIS_PORT,
            decode_responses=True,
        )
        self.data_type = data_type
        self.stock_code = stock_code
        self.running = True

    def read_history(self, count=100):
        """读取历史数据"""
        entries = self.redis.xrange(REDIS_STREAM_KEY, count=count)
        results = []
        for msg_id, msg_data in entries:
            try:
                data = json.loads(msg_data["data"])
                if self._filter(data):
                    results.append((msg_id, data))
            except (json.JSONDecodeError, KeyError):
                continue
        return results

    def read_realtime(self, block_ms=0):
        """实时阻塞读取"""
        last_id = "0"
        while self.running:
            try:
                resp = self.redis.xread(
                    {REDIS_STREAM_KEY: last_id},
                    block=block_ms,
                    count=100,
                )
                if not resp:
                    continue
                for stream_name, messages in resp:
                    for msg_id, msg_data in messages:
                        last_id = msg_id
                        try:
                            data = json.loads(msg_data["data"])
                            if self._filter(data):
                                yield msg_id, data
                        except (json.JSONDecodeError, KeyError):
                            continue
            except redis.ConnectionError:
                print("[!] Redis 连接断开，重连中...")
                try:
                    self.redis.ping()
                except Exception:
                    import time
                    time.sleep(1)
            except KeyboardInterrupt:
                break

    def _filter(self, data):
        """过滤数据"""
        if self.data_type and data.get("data_type") != self.data_type:
            return False
        if self.stock_code and data.get("stock_code") != self.stock_code:
            return False
        return True

    def format_output(self, msg_id, data):
        """格式化输出"""
        ts = data.get("timestamp", "?")
        dtype = data.get("data_type", "?")
        stock = data.get("stock_code", "?")
        return f"[{ts}] {dtype:8s} | {stock}"


def main():
    parser = argparse.ArgumentParser(description="同花顺 L2 实时数据消费端")
    parser.add_argument(
        "--type",
        type=str,
        choices=["snapshot", "depth", "tick", "trend"],
        help="只消费指定类型",
    )
    parser.add_argument("--stock", type=str, help="只消费指定股票代码")
    parser.add_argument("--history", type=int, default=0, help="先读N条历史")
    parser.add_argument("--block", type=int, default=0, help="阻塞等待毫秒数(0=永久)")
    args = parser.parse_args()

    consumer = L2Consumer(data_type=args.type, stock_code=args.stock)

    # 测试连接
    try:
        consumer.redis.ping()
    except redis.ConnectionError:
        print(f"[!] 无法连接 Redis ({REDIS_HOST}:{REDIS_PORT})")
        print("[!] 请确认 Docker Redis 容器已启动:")
        print("    docker start redis-l2")
        sys.exit(1)

    # 显示过滤条件
    filters = []
    if args.type:
        filters.append(f"type={args.type}")
    if args.stock:
        filters.append(f"stock={args.stock}")
    filter_str = " | ".join(filters) if filters else "all"
    print(f"=== 同花顺 L2 实时数据消费端 ===")
    print(f"过滤: {filter_str}")
    print(f"Stream: {REDIS_STREAM_KEY}")
    print(f"当前长度: {consumer.redis.xlen(REDIS_STREAM_KEY)}")
    print(f"按 Ctrl+C 退出\n")

    # 读历史
    if args.history > 0:
        print(f"--- 最近 {args.history} 条历史 ---")
        history = consumer.read_history(args.history)
        for msg_id, data in history:
            print(consumer.format_output(msg_id, data))
        print(f"--- 历史结束，等待实时数据 ---\n")

    # 实时消费
    try:
        for msg_id, data in consumer.read_realtime(block_ms=args.block):
            print(consumer.format_output(msg_id, data))
    except KeyboardInterrupt:
        print("\n退出")


if __name__ == "__main__":
    main()
