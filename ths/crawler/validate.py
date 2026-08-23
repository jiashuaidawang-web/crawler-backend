"""
数据校验脚本
每天爬取后运行，检查数据量是否合理
校验所有同花顺数据源（data_source=0）
"""
import sys
import logging
from datetime import date, datetime, timedelta

from clickhouse_driver import Client
from config import CLICKHOUSE

logging.basicConfig(level=logging.INFO, format="%(asctime)s [%(levelname)s] %(message)s")
logger = logging.getLogger(__name__)


def get_ck_client():
    return Client(
        host=CLICKHOUSE["host"], port=9000,
        database=CLICKHOUSE["database"],
        user=CLICKHOUSE["username"],
        password=CLICKHOUSE["password"],
    )


def get_trading_day(check_date=None):
    """获取要校验的交易日"""
    c = get_ck_client()
    if check_date:
        return check_date
    result = c.execute(
        "SELECT trade_date FROM trade_calendar WHERE is_trading=1 AND trade_date <= today() ORDER BY trade_date DESC LIMIT 1"
    )
    return result[0][0] if result else date.today()


def validate_main_fund_flow(trade_date, obj_type, src_detail, min_count=50, max_count=10000):
    """校验 main_fund_flow 表的数据"""
    c = get_ck_client()
    result = c.execute(
        "SELECT count() FROM main_fund_flow FINAL WHERE trade_date=%(d)s AND src_detail=%(s)s",
        {"d": trade_date, "s": src_detail},
    )
    count = result[0][0]

    # 前一天数据量
    prev_date = trade_date - timedelta(days=7)
    result = c.execute(
        "SELECT count() FROM main_fund_flow FINAL WHERE trade_date=%(d)s AND src_detail=%(s)s",
        {"d": prev_date, "s": src_detail},
    )
    prev_count = result[0][0]

    logger.info(f"=== {obj_type} 数据校验 ({trade_date}) ===")
    logger.info(f"当日数据量: {count} 条")
    logger.info(f"上期数据量: {prev_count} 条")

    warnings = []
    if count < min_count:
        warnings.append(f"数据量异常偏少: {count} < {min_count}")
    if count > max_count:
        warnings.append(f"数据量异常偏多: {count} > {max_count}")
    if prev_count > 0:
        ratio = count / prev_count
        if ratio < 0.5 or ratio > 2.0:
            warnings.append(f"数据量波动过大: 当期/上期 = {ratio:.2f}")

    # 去重检查
    result = c.execute(
        "SELECT count(DISTINCT name) FROM main_fund_flow FINAL WHERE trade_date=%(d)s AND src_detail=%(s)s",
        {"d": trade_date, "s": src_detail},
    )
    unique_count = result[0][0]
    if unique_count != count:
        warnings.append(f"存在重复数据: 总行数={count}, 唯一名称数={unique_count}")

    # 关键字段空值检查
    result = c.execute(
        "SELECT count() FROM main_fund_flow FINAL WHERE trade_date=%(d)s AND src_detail=%(s)s AND main_net IS NULL",
        {"d": trade_date, "s": src_detail},
    )
    null_count = result[0][0]
    if null_count > 0:
        warnings.append(f"存在 {null_count} 条主力净额为 NULL 的数据")

    return warnings


def validate_dragon_tiger(trade_date):
    """校验龙虎榜数据"""
    c = get_ck_client()
    result = c.execute(
        "SELECT count() FROM dragon_tiger FINAL WHERE trade_date=%(d)s AND data_source=0",
        {"d": trade_date},
    )
    count = result[0][0]

    logger.info(f"=== 龙虎榜 数据校验 ({trade_date}) ===")
    logger.info(f"当日数据量: {count} 条")

    warnings = []
    if count < 10:
        warnings.append(f"龙虎榜数据量异常偏少: {count} < 10")

    # 去重检查
    result = c.execute(
        "SELECT count(DISTINCT ts_code) FROM dragon_tiger FINAL WHERE trade_date=%(d)s AND data_source=0",
        {"d": trade_date},
    )
    unique_count = result[0][0]
    if unique_count != count:
        warnings.append(f"存在重复数据: 总行数={count}, 唯一ts_code数={unique_count}")

    return warnings


def validate_dt_detail(trade_date):
    """校验龙虎榜营业部数据"""
    c = get_ck_client()
    result = c.execute(
        "SELECT count() FROM dt_detail FINAL WHERE trade_date=%(d)s AND data_source=0",
        {"d": trade_date},
    )
    count = result[0][0]

    logger.info(f"=== 龙虎榜营业部 数据校验 ({trade_date}) ===")
    logger.info(f"当日数据量: {count} 条")

    warnings = []
    if count < 20:
        warnings.append(f"龙虎榜营业部数据量异常偏少: {count} < 20")

    return warnings


def validate_northbound(trade_date):
    """校验北向资金数据
    注意：北向资金API返回的是历史数据（从2015年至今），
    数据中的trade_date是实际交易日期，不是传入的trade_date参数
    """
    c = get_ck_client()
    # 检查同花顺北向资金总数据量
    result = c.execute(
        "SELECT count() FROM northbound_flow FINAL WHERE data_source=0 AND src_detail='ths_northbound'"
    )
    total_count = result[0][0]

    # 检查最新数据日期
    result = c.execute(
        "SELECT max(trade_date) FROM northbound_flow FINAL WHERE data_source=0 AND src_detail='ths_northbound'"
    )
    max_date = result[0][0]

    logger.info(f"=== 北向资金 数据校验 ===")
    logger.info(f"同花顺北向资金总量: {total_count} 条")
    logger.info(f"最新数据日期: {max_date}")

    warnings = []
    if total_count < 1:
        warnings.append("北向资金数据量为 0")

    return warnings


def main():
    import argparse
    parser = argparse.ArgumentParser(description="数据校验脚本")
    parser.add_argument("--date", type=str, default=None, help="校验日期 (YYYY-MM-DD)")
    args = parser.parse_args()

    trade_date = get_trading_day(args.date)
    # 确保是 date 类型
    if isinstance(trade_date, str):
        trade_date = datetime.strptime(trade_date, "%Y-%m-%d").date()
    logger.info(f"校验日期: {trade_date}")

    all_warnings = []

    # 行业资金流
    all_warnings.extend(validate_main_fund_flow(
        trade_date, "行业资金流", "ths_fund_flow", min_count=50, max_count=2000
    ))

    # 概念资金流
    all_warnings.extend(validate_main_fund_flow(
        trade_date, "概念资金流", "ths_concept_fund_flow", min_count=100, max_count=1000
    ))

    # 个股资金流
    all_warnings.extend(validate_main_fund_flow(
        trade_date, "个股资金流", "ths_stock_fund", min_count=1000, max_count=10000
    ))

    # 龙虎榜
    all_warnings.extend(validate_dragon_tiger(trade_date))

    # 龙虎榜营业部
    all_warnings.extend(validate_dt_detail(trade_date))

    # 北向资金
    all_warnings.extend(validate_northbound(trade_date))

    # 汇总
    if all_warnings:
        logger.warning("=== 校验发现问题 ===")
        for w in all_warnings:
            logger.warning(f"  ⚠ {w}")
        sys.exit(1)
    else:
        logger.info("✅ 全部数据校验通过")
        sys.exit(0)


if __name__ == "__main__":
    main()
