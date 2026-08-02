"""
AKShare 接口字段验证脚本
验证目标：确认各接口真实返回的列名、单位、格式
输出：每接口的 columns.tolist() + 前2行样例数据
"""
import json
import traceback
import os

# 禁用所有代理（curl_cffi / requests 可能读系统代理导致连接失败）
for k in ["HTTP_PROXY", "HTTPS_PROXY", "http_proxy", "https_proxy", "ALL_PROXY", "all_proxy"]:
    os.environ.pop(k, None)
os.environ["NO_PROXY"] = "*"

import akshare as ak
import pandas as pd

# 全局结果收集
results = {}


def check(name, fn, *args, **kwargs):
    """调用接口，捕获列名 + 样例 + 异常"""
    print(f"\n{'='*70}")
    print(f"▶ {name}")
    print(f"  调用: {fn.__name__}({', '.join(map(repr, args))})")
    print(f"{'='*70}")
    try:
        df = fn(*args, **kwargs)
        if df is None or df.empty:
            print("  ⚠️ 返回空 DataFrame")
            results[name] = {"columns": [], "empty": True, "error": None}
            return
        cols = df.columns.tolist()
        print(f"  列数: {len(cols)}")
        print(f"  列名: {cols}")
        print(f"\n  样例 (前2行):")
        # 转成可 JSON 序列化的格式
        sample = df.head(2).astype(str).to_dict(orient="records")
        for i, row in enumerate(sample):
            print(f"  --- 行 {i+1} ---")
            for k, v in row.items():
                print(f"    {k}: {v}")
        results[name] = {
            "columns": cols,
            "empty": False,
            "sample": sample,
            "dtypes": {c: str(t) for c, t in df.dtypes.items()},
            "error": None,
        }
    except Exception as e:
        err = traceback.format_exc()
        print(f"  ❌ 异常: {e}")
        results[name] = {"columns": [], "empty": True, "error": str(e)}


# ======================================================================
# A. 个股日线
# ======================================================================
check(
    "A. stock_daily (个股日线)",
    ak.stock_zh_a_hist,
    symbol="600030",
    period="daily",
    start_date="20260801",
    end_date="20260802",
    adjust="qfq",
)

# ======================================================================
# B. 个股周线
# ======================================================================
check(
    "B. stock_weekly (个股周线)",
    ak.stock_zh_a_hist,
    symbol="600030",
    period="weekly",
    start_date="20260101",
    end_date="20260802",
    adjust="qfq",
)

# ======================================================================
# C. 指数日线
# ======================================================================
check(
    "C. index_daily (指数日线 - stock_zh_index_daily)",
    ak.stock_zh_index_daily,
    symbol="sh000001",
)

# ======================================================================
# D. 板块日线 (行业)
# ======================================================================
check(
    "D. board_daily (行业板块日线)",
    ak.stock_board_industry_hist_em,
    symbol="小金属",
    period="日线",
    start_date="20260801",
    end_date="20260802",
    adjust="qfq",
)

# ======================================================================
# D2. 板块日线 (概念)
# ======================================================================
check(
    "D2. board_daily (概念板块日线)",
    ak.stock_board_concept_hist_em,
    symbol="半导体",
    period="日线",
    start_date="20260801",
    end_date="20260802",
    adjust="qfq",
)

# ======================================================================
# D3. 板块涨跌家数 (补 up_count / down_count)
# 注意：akshare 1.18.x 无 stock_board_industry_change_em，改用 summary 接口
# ======================================================================
check(
    "D3. board_summary (板块涨跌家数/领涨)",
    ak.stock_board_industry_summary_ths,
)

# ======================================================================
# E. 主力资金流 (个股) — 正确接口名 stock_individual_fund_flow
# ======================================================================
check(
    "E. main_fund_flow (个股资金流)",
    ak.stock_individual_fund_flow,
    stock="600030",
    market="sh",
)

# ======================================================================
# E2. 板块资金流
# ======================================================================
check(
    "E2. main_fund_flow (板块资金流 stock_sector_fund_flow_hist)",
    ak.stock_sector_fund_flow_hist,
    symbol="半导体",
)

# ======================================================================
# F. 龙虎榜 — 拉大日期范围确保有数据
# ======================================================================
check(
    "F. dragon_tiger (龙虎榜详情)",
    ak.stock_lhb_detail_em,
    start_date="20260728",
    end_date="20260802",
)

# ======================================================================
# G. 涨停池
# ======================================================================
check(
    "G. limit_pool (涨停池 stock_zt_pool_em)",
    ak.stock_zt_pool_em,
    date="20260802",
)

# ======================================================================
# G2. 跌停池
# ======================================================================
check(
    "G2. limit_pool (跌停池 stock_zt_pool_dtgc_em)",
    ak.stock_zt_pool_dtgc_em,
    date="20260802",
)

# ======================================================================
# G3. 炸板池
# ======================================================================
check(
    "G3. limit_pool (炸板池 stock_zt_pool_zbgc_em)",
    ak.stock_zt_pool_zbgc_em,
    date="20260802",
)

# ======================================================================
# G4. 强势池
# ======================================================================
check(
    "G4. limit_pool (强势池 stock_zt_pool_strong_em)",
    ak.stock_zt_pool_strong_em,
    date="20260802",
)

# ======================================================================
# G5. 次新池
# ======================================================================
check(
    "G5. limit_pool (次新池 stock_zt_pool_sub_new_em)",
    ak.stock_zt_pool_sub_new_em,
    date="20260802",
)

# ======================================================================
# H. 北向资金 (stock_hsgt_fund_flow_summary_em — 无参数，返回全量)
# ======================================================================
check(
    "H. northbound_flow (北向资金 stock_hsgt_fund_flow_summary_em)",
    ak.stock_hsgt_fund_flow_summary_em,
)

# ======================================================================
# 额外：股票列表 (stock universe)
# ======================================================================
check(
    "I. stock_universe (A股实时行情 stock_zh_a_spot_em)",
    ak.stock_zh_a_spot_em,
)

# ======================================================================
# 汇总输出
# ======================================================================
print("\n\n")
print("#" * 70)
print("# 汇总：各接口列名一览")
print("#" * 70)
for name, info in results.items():
    cols = info.get("columns", [])
    n = len(cols)
    err = info.get("error")
    empty = info.get("empty")
    status = "❌" if err else ("⚠️空" if empty else "✅")
    print(f"\n{status} {name}  ({n} 列)")
    if err:
        print(f"   错误: {err[:200]}")
    else:
        for c in cols:
            print(f"   - {c}")

# 保存完整 JSON 结果，供后续解析
with open("akshare_verify_result.json", "w", encoding="utf-8") as f:
    # 去掉 sample 里的非序列化内容
    json.dump(results, f, ensure_ascii=False, indent=2, default=str)
print(f"\n\n完整结果已保存到 akshare_verify_result.json")
