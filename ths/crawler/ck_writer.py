"""
ClickHouse 写入器 - 同花顺资金流向
直接写入 ClickHouse，无中间文件
data_source = 0 表示同花顺数据（区别于东方财富的 1）

注意：clickhouse-driver 按位置传值，dict 顺序必须与表列顺序一致
"""
from datetime import date, datetime
from clickhouse_driver import Client

from config import CLICKHOUSE


class CkWriter:
    def __init__(self):
        self.client = Client(
            host=CLICKHOUSE["host"],
            port=9000,
            database=CLICKHOUSE["database"],
            user=CLICKHOUSE["username"],
            password=CLICKHOUSE["password"],
            settings={"use_numpy": False},
        )

    def test_connection(self):
        """测试连接"""
        try:
            result = self.client.execute("SELECT 1")
            return result[0][0] == 1
        except Exception as e:
            print(f"[CK] 连接失败: {e}")
            return False

    def insert_industry_fund_flow(self, rows, trade_date):
        """写入行业资金流 → main_fund_flow 表
        列顺序: trade_date, obj_type, ts_code, board_code, index_code, name,
                main_net, super_big, big_net, mid_net, small_net,
                data_source, src_detail, create_date, update_date
        """
        data = []
        for row in rows:
            if len(row) < 7:
                continue
            data.append((
                trade_date,           # trade_date
                "board",              # obj_type
                None,                 # ts_code
                row[1],               # board_code
                None,                 # index_code
                row[1],               # name
                self._parse_yi(row[6]),  # main_net
                None,                 # super_big
                self._parse_yi(row[4]),  # big_net
                self._parse_yi(row[5]),  # mid_net
                None,                 # small_net
                0,                    # data_source (同花顺)
                "ths_fund_flow",      # src_detail
                date.today(),         # create_date
                datetime.now(),       # update_date
            ))
        if data:
            self.client.execute(
                "INSERT INTO main_fund_flow VALUES",
                data, types_check=True,
            )
        return len(data)

    def insert_concept_fund_flow(self, rows, trade_date):
        """写入概念资金流 → main_fund_flow 表"""
        data = []
        for row in rows:
            if len(row) < 7:
                continue
            data.append((
                trade_date,
                "concept",
                None,
                row[1],
                None,
                row[1],
                self._parse_yi(row[6]),
                None,
                self._parse_yi(row[4]),
                self._parse_yi(row[5]),
                None,
                0,
                "ths_concept_fund_flow",
                date.today(),
                datetime.now(),
            ))
        if data:
            self.client.execute(
                "INSERT INTO main_fund_flow VALUES",
                data, types_check=True,
            )
        return len(data)

    def insert_stock_fund_flow(self, rows, trade_date):
        """写入个股资金流 → main_fund_flow 表"""
        data = []
        for row in rows:
            if len(row) < 9:
                continue
            data.append((
                trade_date,
                "stock",
                row[1],
                None,
                None,
                row[2],
                self._parse_yuan(row[8]),
                None,
                self._parse_yuan(row[10]) if len(row) > 10 else None,
                self._parse_yuan(row[6]),
                self._parse_yuan(row[7]),
                0,
                "ths_stock_fund",
                date.today(),
                datetime.now(),
            ))
        if data:
            self.client.execute(
                "INSERT INTO main_fund_flow VALUES",
                data, types_check=True,
            )
        return len(data)

    def insert_dragon_tiger(self, rows, trade_date):
        """写入龙虎榜 → dragon_tiger 表"""
        data = []
        for row in rows:
            if len(row) < 7:
                continue
            data.append((
                trade_date,
                row[1],                          # ts_code
                row[2],                          # stock_name
                None,                            # reason
                None,                            # explanation
                row[0] if row[0] else None,      # abnormal_type
                self._parse_yuan(row[6]),        # net_buy
                None,                            # total_buy
                None,                            # total_sell
                self._parse_yuan(row[5]),        # billboard_deal_amt
                None,                            # accum_amount
                None,                            # buy_ratio
                None,                            # sell_ratio
                None,                            # buy_seat
                None,                            # sell_seat
                None,                            # buy_seat_new
                None,                            # sell_seat_new
                self._parse_pct(row[4]),         # change_rate
                self._parse_price(row[3]),       # close_price
                None,                            # turnoverrate
                None,                            # free_market_cap
                None,                            # market
                None,                            # deal_amount_ratio
                None,                            # deal_net_ratio
                None,                            # security_inner_code
                None,                            # security_type_code
                None,                            # trade_id
                None,                            # trade_market
                None,                            # trade_market_code
                self._parse_yuan(row[6]),        # net_bs_amt
                None,                            # sum_buy_amt
                None,                            # sum_sell_amt
                None,                            # d1_close_adjchrate
                None,                            # d2_close_adjchrate
                None,                            # d5_close_adjchrate
                None,                            # d10_close_adjchrate
                None,                            # d20_close_adjchrate
                None,                            # d30_close_adjchrate
                0,                               # data_source (同花顺)
                "ths_lhb",                       # src_detail
                date.today(),                    # create_date
                datetime.now(),                  # update_date
            ))
        if data:
            self.client.execute(
                "INSERT INTO dragon_tiger VALUES",
                data, types_check=True,
            )
        return len(data)

    def insert_lhb_seat(self, rows, trade_date):
        """写入龙虎榜营业部 → dt_detail 表"""
        data = []
        for row in rows:
            if len(row) < 7:
                continue
            data.append((
                trade_date,
                "",                              # ts_code
                row[1],                          # seat_name
                None,                            # seat_type
                int(row[0]) if row[0].isdigit() else None,  # rank
                self._parse_yuan(row[3]),        # buy
                None,                            # sell
                self._parse_yuan(row[3]),        # net_buy
                None,                            # buy_ratio
                None,                            # sell_ratio
                None,                            # net_buy_ratio
                self._parse_yuan(row[3]),        # trade_amt
                None,                            # trade_ratio
                None,                            # accum_volume
                self._parse_yuan(row[3]),        # accum_amount
                None,                            # change_rate
                None,                            # turnoverrate_ratio
                None,                            # trade_direction
                None,                            # statistics_days
                int(row[2]) if row[2].isdigit() else None,  # onlist_times
                None,                            # start_date
                None,                            # end_date
                None,                            # operate_dept_code
                None,                            # operate_dept_type
                None,                            # change_type
                None,                            # explanation
                None,                            # trade_id
                None,                            # security_inner_code
                None,                            # sec_type
                0,                               # data_source (同花顺)
                "ths_lhb_seat",                  # src_detail
                date.today(),                    # create_date
                datetime.now(),                  # update_date
            ))
        if data:
            self.client.execute(
                "INSERT INTO dt_detail VALUES",
                data, types_check=True,
            )
        return len(data)

    def insert_northbound(self, rows):
        """写入北向资金 → northbound_flow 表
        列顺序: trade_date, data_source, direction, time_point,
                net_inflow, buy_amount, sell_amount, cumulative_net_inflow,
                status_flag, src_detail, create_date, update_date,
                sh_net, sz_net, hk_hold_net
        """
        data = []
        for row in rows:
            if len(row) < 6:
                continue
            td = self._parse_date(row[0])
            if not td:
                continue
            data.append((
                td,                              # trade_date
                0,                               # data_source (同花顺)
                "north",                         # direction
                "",                              # time_point
                self._parse_yuan(row[1]),        # net_inflow
                self._parse_yuan(row[4]),        # buy_amount
                self._parse_yuan(row[5]),        # sell_amount
                self._parse_yuan(row[3]),        # cumulative_net_inflow
                None,                            # status_flag
                "ths_northbound",                # src_detail
                date.today(),                    # create_date
                datetime.now(),                  # update_date
                0.0,                             # sh_net
                0.0,                             # sz_net
                0.0,                             # hk_hold_net
            ))
        if data:
            self.client.execute(
                "INSERT INTO northbound_flow VALUES",
                data, types_check=True,
            )
        return len(data)

    # ===== 数据解析工具 =====

    @staticmethod
    def _parse_yuan(val):
        """解析金额（元）: '1.23亿' → 123000000"""
        if not val:
            return None
        val = val.strip().replace(",", "")
        try:
            if "亿" in val:
                return float(val.replace("亿", "")) * 1e8
            elif "万" in val:
                return float(val.replace("万", "")) * 1e4
            else:
                return float(val)
        except (ValueError, TypeError):
            return None

    @staticmethod
    def _parse_yi(val):
        """解析亿为元"""
        if not val:
            return None
        val = val.strip().replace(",", "")
        try:
            return float(val) * 1e8
        except (ValueError, TypeError):
            return None

    @staticmethod
    def _parse_pct(val):
        """解析百分比: '5.34%' → 5.34"""
        if not val:
            return None
        try:
            return float(val.strip().replace("%", ""))
        except (ValueError, TypeError):
            return None

    @staticmethod
    def _parse_price(val):
        """解析价格"""
        if not val:
            return None
        try:
            return float(val.strip())
        except (ValueError, TypeError):
            return None

    @staticmethod
    def _parse_date(val):
        """解析日期: '2024-08-16' → date"""
        if not val:
            return None
        try:
            parts = val.strip().split("-")
            if len(parts) == 3:
                return date(int(parts[0]), int(parts[1]), int(parts[2]))
        except (ValueError, TypeError):
            pass
        return None
