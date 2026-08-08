#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
akshare-bridge —— crawler-backend 的财报数据源桥接服务。

背景
----
东财 datacenter 报告接口当前统一返回 code=9501（"返回字段参数不能为空"），无法直接抓取财报；
emweb F10 又有反爬重定向。因此 financial 表改用 akshare 作为数据源：akshare 已封装好
新浪/东财财报页面解析，稳定返回 营收/净利润/同比/ROE。

本服务以极薄 HTTP 层暴露给 crawler-admin 的 FinancialSeeder：
    POST /financial      body {"ts_code":"000001","start_year":"2018"}
                        -> {"ts_code":"000001.SZ","rows":[{ts_code,end_date,report_type,ann_date,revenue,net_profit,net_profit_yoy,roe}, ...]}
    GET  /healthz       -> {"ok":true,"akshare":true}

部署（与 crawler 同机即可，无需额外代理，只要该机能访问东财/新浪）：
    pip install -r requirements.txt
    uvicorn akshare_bridge:app --host 0.0.0.0 --port 8800
然后在 crawler-admin application.yml 打开：
    akshare.bridge.enabled: true
    akshare.bridge.url: http://localhost:8800
"""
import math
import os
import time
from typing import Optional

import uvicorn
from fastapi import FastAPI, HTTPException
from pydantic import BaseModel

try:
    import akshare as ak
except Exception as e:  # pragma: no cover
    ak = None
    print("[akshare-bridge] WARNING: akshare 未安装 ->", e)

app = FastAPI(title="akshare-bridge", version="1.0.0")

# stock_financial_abstract 的宽表列名（指标行 -> 各报告期列）
NON_PERIOD_COLS = ("选项", "指标")


# --------------------------------------------------------------------------
# 工具
# --------------------------------------------------------------------------
def to_ts_code(code: str) -> str:
    """6 位代码补交易所后缀（与 crawler 的 ts_code 约定一致）。"""
    code = (code or "").strip().upper()
    if "." in code:
        return code
    if code[:1] in ("6", "9") or code.startswith("68") or code.startswith("69"):
        return code + ".SH"
    if code[:1] in ("0", "3", "2") or code.startswith(("00", "30", "20")):
        return code + ".SZ"
    if code[:1] in ("4", "8"):
        return code + ".BJ"
    return code + ".SZ"


def parse_period_end(period: str) -> Optional[str]:
    """'20260331' / '2026-03-31' / '2026/3/31' -> 'YYYY-MM-DD' 或 None。"""
    if not period:
        return None
    s = str(period).strip().replace("/", "-").replace(" ", "")
    if len(s) == 8 and s.isdigit():
        y, m, d = s[0:4], s[4:6], s[6:8]
    else:
        parts = s.split("-")
        if len(parts) < 3:
            return None
        y, m, d = parts[0], parts[1], parts[2]
    try:
        return f"{int(y):04d}-{int(m):02d}-{int(d):02d}"
    except ValueError:
        return None


def report_type_of(end_date: str) -> Optional[str]:
    if not end_date:
        return None
    mmdd = end_date[5:]
    return {"03-31": "Q1", "06-30": "Q2", "09-30": "Q3", "12-31": "年报"}.get(mmdd)


def _num(v):
    if v is None:
        return None
    if isinstance(v, float) and math.isnan(v):
        return None
    if isinstance(v, (int, float)):
        return float(v)
    s = str(v).strip().replace(",", "").replace("%", "")
    if s in ("", "--", "-", "None", "nan"):
        return None
    try:
        return float(s)
    except ValueError:
        return None


# --------------------------------------------------------------------------
# 财报抽取
# --------------------------------------------------------------------------
def to_em_symbol(code: str) -> str:
    """转 akshare 东财接口要求的交易所前缀代码：SH600519 / SZ000001 / BJxxxxx。"""
    code = (code or "").strip().upper()
    bare = code.split(".")[0]
    suffix = code.split(".")[1] if "." in code else ""
    if suffix == "SH":
        return "SH" + bare
    if suffix == "SZ":
        return "SZ" + bare
    if suffix == "BJ":
        return "BJ" + bare
    if bare[:1] in ("6", "9") or bare.startswith(("68", "69")):
        return "SH" + bare
    if bare[:1] in ("4", "8"):
        return "BJ" + bare
    return "SZ" + bare


def financial_rows(code: str, start_year: str, retries: int = 3, delay: float = 0.6):
    """调用 akshare 东财年度利润表（stock_profit_sheet_by_yearly_em）抽取财报。

    为什么不用 stock_financial_abstract：该函数硬编码 Sina 的 sh 前缀，深/北交所
    股票全部返回空；且 Sina 对本机批量请求直接“拒绝访问”。东财接口稳定可达，
    给出绝对营收/归母净利润及同比。批量时偶发抖动，做重试+退避提升成功率。
    """
    if ak is None:
        raise HTTPException(status_code=503, detail="akshare 未安装")
    em = to_em_symbol(code)
    df = None
    last_err = None
    for attempt in range(retries):
        try:
            df = ak.stock_profit_sheet_by_yearly_em(symbol=em)
            if df is not None and not df.empty:
                break
        except Exception as e:  # 东财偶发抖动
            last_err = e
        time.sleep(delay)
    if df is None or df.empty:
        raise HTTPException(status_code=502, detail=f"akshare 东财调用失败(重试{retries}次): {last_err}")

    def pick(row, *cands):
        for c in cands:
            if c in row and row[c] is not None:
                v = _num(row[c])
                if v is not None:
                    return v
        return None

    out = []
    seen = set()
    for _, row in df.iterrows():
        end_date = parse_period_end(str(row.get("REPORT_DATE"))[:10])
        if not end_date or end_date in seen:
            continue
        seen.add(end_date)
        if start_year and end_date[:4] < str(start_year):
            continue
        revenue = pick(row, "TOTAL_OPERATE_INCOME", "OPERATE_INCOME")
        net_profit = pick(row, "PARENT_NETPROFIT", "NETPROFIT")
        yoy = pick(row, "PARENT_NETPROFIT_YOY", "NETPROFIT_YOY", "TOTAL_OPERATE_INCOME_YOY")
        ann_date = parse_period_end(str(row.get("NOTICE_DATE"))[:10])
        out.append({
            "ts_code": to_ts_code(code),
            "end_date": end_date,
            "report_type": report_type_of(end_date) or "年报",
            "ann_date": ann_date,
            "revenue": revenue,
            "net_profit": net_profit,
            "net_profit_yoy": yoy,
            "roe": None,
        })
    return out


# --------------------------------------------------------------------------
# API
# --------------------------------------------------------------------------
@app.get("/healthz")
def healthz():
    return {"ok": True, "akshare": ak is not None}


# --------------------------------------------------------------------------
# 交易日历（trade_calendar）—— 权威来源：akshare tool_trade_date_hist_sina
# --------------------------------------------------------------------------
@app.get("/trade-calendar")
def trade_calendar(from_date: Optional[str] = None, to_date: Optional[str] = None):
    """返回 A 股真实交易日列表（已排除周末 + 法定假日 + 调休上班日）。

    GET /trade-calendar?from_date=2020-01-01&to_date=2030-12-31
    -> {"count": 2738, "trade_dates":["2020-01-02","2020-01-03", ...]}
    from/to 为空则返回全量（1990-至今）。
    """
    if ak is None:
        raise HTTPException(status_code=503, detail="akshare 未安装")
    import pandas as pd
    try:
        df = ak.tool_trade_date_hist_sina()
    except Exception as e:
        raise HTTPException(status_code=502, detail=f"akshare 交易日历调用失败: {e}")
    if df is None or df.empty:
        raise HTTPException(status_code=502, detail="akshare 返回空交易日历")
    dates = pd.to_datetime(df["trade_date"])
    if from_date:
        dates = dates[dates >= pd.to_datetime(from_date)]
    if to_date:
        dates = dates[dates <= pd.to_datetime(to_date)]
    out = [d.strftime("%Y-%m-%d") for d in dates]
    return {"count": len(out), "trade_dates": out}


class FinancialReq(BaseModel):
    ts_code: str
    start_year: str = "2018"


@app.post("/financial")
def financial(req: FinancialReq):
    rows = financial_rows(req.ts_code, req.start_year)
    return {"ts_code": to_ts_code(req.ts_code), "rows": rows}


if __name__ == "__main__":
    port = int(os.environ.get("PORT", "8800"))
    uvicorn.run(app, host="0.0.0.0", port=port)
