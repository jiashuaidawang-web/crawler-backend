@echo off
REM ============================================
REM 同花顺概念资金流爬虫 - Windows 每日运行
REM 建议通过任务计划程序在交易日 18:00 触发
REM ============================================

cd /d %~dp0

REM 运行爬虫
echo [%date% %time%] 开始爬取 >> crawler.log
python main.py >> crawler.log 2>&1

if %ERRORLEVEL% NEQ 0 (
    echo [%date% %time%] 爬虫运行失败 >> error.log
    exit /b %ERRORLEVEL%
)

REM 数据校验
python validate.py >> crawler.log 2>&1

if %ERRORLEVEL% NEQ 0 (
    echo [%date% %time%] 数据校验失败 >> error.log
    exit /b %ERRORLEVEL%
)

echo [%date% %time%] 爬取+校验完成 >> crawler.log
