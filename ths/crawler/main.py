"""
同花顺资金流向爬虫 - 全量数据
抓取: 行业资金流、概念资金流、个股资金流、龙虎榜、龙虎榜营业部、北向资金
所有数据 data_source=0 区别于东方财富(data_source=1)

用法:
    python main.py                    # 全量抓取
    python main.py --pages 5          # 只抓前5页（测试用）
    python main.py --date 2026-08-21  # 指定日期
    python main.py --type industry    # 只抓行业
    python main.py --type concept     # 只抓概念
    python main.py --type stock       # 只抓个股
    python main.py --type lhb         # 只抓龙虎榜
    python main.py --type seat        # 只抓龙虎榜营业部
    python main.py --type north       # 只抓北向资金
"""
import argparse
import sys
import logging
from datetime import date, datetime

# 配置日志
logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s [%(levelname)s] %(message)s",
    handlers=[
        logging.FileHandler("crawler.log", encoding="utf-8"),
        logging.StreamHandler(),
    ],
)
logger = logging.getLogger(__name__)

from config import THS_FUND_BASE
from api_client import ThsApiClient
from ck_writer import CkWriter


def is_trading_day(check_date):
    """从 ClickHouse trade_calendar 表判断是否为交易日"""
    from clickhouse_driver import Client
    from config import CLICKHOUSE
    try:
        c = Client(
            host=CLICKHOUSE["host"], port=9000,
            database=CLICKHOUSE["database"],
            user=CLICKHOUSE["username"],
            password=CLICKHOUSE["password"],
        )
        result = c.execute(
            "SELECT is_trading FROM trade_calendar WHERE trade_date=%(d)s",
            {"d": check_date},
        )
        if result:
            return result[0][0] == 1
        return False
    except Exception:
        return False


def get_last_trading_day(check_date):
    """获取最近的交易日（含当天）"""
    from clickhouse_driver import Client
    from config import CLICKHOUSE
    try:
        c = Client(
            host=CLICKHOUSE["host"], port=9000,
            database=CLICKHOUSE["database"],
            user=CLICKHOUSE["username"],
            password=CLICKHOUSE["password"],
        )
        result = c.execute(
            "SELECT trade_date FROM trade_calendar WHERE trade_date<=%(d)s AND is_trading=1 ORDER BY trade_date DESC LIMIT 1",
            {"d": check_date},
        )
        if result:
            return result[0][0]
        return check_date
    except Exception:
        return check_date


def crawl_industry_fund_flow(client, writer, trade_date, max_pages=999):
    """行业资金流"""
    logger.info("[行业资金流] 开始抓取...")
    base_url = f"{THS_FUND_BASE}/funds/hyzjl/field/tradezdf/order/desc"
    referer = f"{THS_FUND_BASE}/funds/hyzjl/"
    total = 0
    page = 1
    while page <= max_pages:
        url = f"{base_url}/page/{page}/ajax/1/free/1/"
        html = client.get(url, referer)
        headers, rows = client.parse_html_table(html)
        if not rows:
            break
        n = writer.insert_industry_fund_flow(rows, trade_date)
        total += n
        logger.info(f"  第{page}页: {n} 条")
        total_pages = client.get_total_pages(html)
        if page >= total_pages:
            break
        page += 1
    logger.info(f"[行业资金流] 完成: 共 {total} 条")
    return total


def crawl_concept_fund_flow(client, writer, trade_date, max_pages=999):
    """概念资金流"""
    logger.info("[概念资金流] 开始抓取...")
    base_url = f"{THS_FUND_BASE}/funds/gnzjl/field/tradezdf/order/desc"
    referer = f"{THS_FUND_BASE}/funds/gnzjl/"
    total = 0
    page = 1
    while page <= max_pages:
        url = f"{base_url}/page/{page}/ajax/1/free/1/"
        html = client.get(url, referer)
        headers, rows = client.parse_html_table(html)
        if not rows:
            break
        n = writer.insert_concept_fund_flow(rows, trade_date)
        total += n
        logger.info(f"  第{page}页: {n} 条")
        total_pages = client.get_total_pages(html)
        if page >= total_pages:
            break
        page += 1
    logger.info(f"[概念资金流] 完成: 共 {total} 条")
    return total


def crawl_stock_fund_flow(client, writer, trade_date, max_pages=999):
    """个股资金流"""
    logger.info("[个股资金流] 开始抓取...")
    base_url = f"{THS_FUND_BASE}/funds/ggzjl/field/zdf/order/desc"
    referer = f"{THS_FUND_BASE}/funds/ggzjl/"
    total = 0
    page = 1
    while page <= max_pages:
        url = f"{base_url}/page/{page}/ajax/1/free/1/"
        html = client.get(url, referer)
        headers, rows = client.parse_html_table(html)
        if not rows:
            break
        n = writer.insert_stock_fund_flow(rows, trade_date)
        total += n
        logger.info(f"  第{page}页: {n} 条")
        total_pages = client.get_total_pages(html)
        if page >= total_pages:
            break
        page += 1
    logger.info(f"[个股资金流] 完成: 共 {total} 条")
    return total


def crawl_dragon_tiger(client, writer, trade_date, max_pages=999):
    """龙虎榜"""
    logger.info("[龙虎榜] 开始抓取...")
    url = f"{THS_FUND_BASE}/ifmarket/lhbtable/stock/all/ajax/1/"
    referer = f"{THS_FUND_BASE}/ifmarket/lhbtable/"
    html = client.get(url, referer)
    headers, rows = client.parse_html_table(html)
    if rows:
        n = writer.insert_dragon_tiger(rows, trade_date)
        logger.info(f"  龙虎榜: {n} 条")
    else:
        n = 0
        logger.info("  龙虎榜: 无数据")
    logger.info(f"[龙虎榜] 完成: 共 {n} 条")
    return n


def crawl_lhb_seat(client, writer, trade_date, max_pages=999):
    """龙虎榜营业部"""
    logger.info("[龙虎榜营业部] 开始抓取...")
    date_str = trade_date.strftime("%Y-%m-%d")
    url = f"{THS_FUND_BASE}/ifmarket/lhbyyb/report/{date_str}/ajax/1/"
    referer = f"{THS_FUND_BASE}/ifmarket/lhbyyb/"
    html = client.get(url, referer)
    headers, rows = client.parse_html_table(html)
    if rows:
        n = writer.insert_lhb_seat(rows, trade_date)
        logger.info(f"  龙虎榜营业部: {n} 条")
    else:
        n = 0
        logger.info("  龙虎榜营业部: 无数据")
    logger.info(f"[龙虎榜营业部] 完成: 共 {n} 条")
    return n


def crawl_northbound(client, writer, trade_date, max_pages=999):
    """北向资金"""
    logger.info("[北向资金] 开始抓取...")
    url = f"{THS_FUND_BASE}/hgt/hgtb/board/getHgtPage/ajax/1/"
    referer = f"{THS_FUND_BASE}/hgt/hgtb/"
    html = client.get(url, referer)
    headers, rows = client.parse_html_table(html)
    if rows:
        n = writer.insert_northbound(rows)
        logger.info(f"  北向资金: {n} 条")
    else:
        n = 0
        logger.info("  北向资金: 无数据")
    logger.info(f"[北向资金] 完成: 共 {n} 条")
    return n


# 所有爬虫类型
CRAWL_TYPES = {
    "industry": crawl_industry_fund_flow,
    "concept": crawl_concept_fund_flow,
    "stock": crawl_stock_fund_flow,
    "lhb": crawl_dragon_tiger,
    "seat": crawl_lhb_seat,
    "north": crawl_northbound,
}


def main():
    parser = argparse.ArgumentParser(description="同花顺资金流向爬虫 - 全量数据")
    parser.add_argument("--pages", type=int, default=999, help="最大页数")
    parser.add_argument(
        "--date",
        type=str,
        default=None,
        help="交易日期 (YYYY-MM-DD)，默认自动判断",
    )
    parser.add_argument(
        "--type",
        type=str,
        default=None,
        choices=list(CRAWL_TYPES.keys()),
        help="只抓取指定类型",
    )
    args = parser.parse_args()

    # 自动判断交易日期
    if args.date:
        trade_date = datetime.strptime(args.date, "%Y-%m-%d").date()
    else:
        today = date.today()
        now_hour = datetime.now().hour
        if is_trading_day(today) and now_hour >= 15:
            trade_date = today
        else:
            trade_date = get_last_trading_day(today)
        trade_date = trade_date or today

    # 验证是交易日
    if not is_trading_day(trade_date):
        logger.warning(f"{trade_date} 不是交易日，数据可能为空")

    # 初始化
    client = ThsApiClient()
    writer = CkWriter()

    # 测试 ClickHouse 连接
    if not writer.test_connection():
        logger.error("ClickHouse 连接失败，请检查配置")
        sys.exit(1)

    logger.info(f"=== 同花顺资金流向爬虫 ===")
    logger.info(f"交易日期: {trade_date}")
    logger.info(f"开始时间: {datetime.now().strftime('%H:%M:%S')}")

    # 确定要运行的爬虫
    if args.type:
        crawl_funcs = {args.type: CRAWL_TYPES[args.type]}
    else:
        crawl_funcs = CRAWL_TYPES

    # 执行抓取
    results = {}
    for name, func in crawl_funcs.items():
        try:
            count = func(client, writer, trade_date, args.pages)
            results[name] = count
        except Exception as e:
            logger.error(f"[{name}] 抓取异常: {e}", exc_info=True)
            results[name] = 0

    # 汇总
    logger.info(f"=== 抓取完成 ===")
    for name, count in results.items():
        logger.info(f"  {name}: {count} 条")
    total = sum(results.values())
    logger.info(f"总计: {total} 条")
    logger.info(f"结束时间: {datetime.now().strftime('%H:%M:%S')}")


if __name__ == "__main__":
    main()
