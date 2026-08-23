#!/bin/bash
# ============================================
# 同花顺概念资金流爬虫 - Linux 每日运行
# crontab: 0 18 * * 1-5 /path/to/run_daily.sh
# ============================================

cd "$(dirname "$0")"

echo "[$(date '+%Y-%m-%d %H:%M:%S')] 开始爬取" >> crawler.log

# 运行爬虫
python3 main.py >> crawler.log 2>&1
if [ $? -ne 0 ]; then
    echo "[$(date '+%Y-%m-%d %H:%M:%S')] 爬虫运行失败" >> error.log
    exit 1
fi

# 数据校验
python3 validate.py >> crawler.log 2>&1
if [ $? -ne 0 ]; then
    echo "[$(date '+%Y-%m-%d %H:%M:%S')] 数据校验失败" >> error.log
    exit 1
fi

echo "[$(date '+%Y-%m-%d %H:%M:%S')] 爬取+校验完成" >> crawler.log
