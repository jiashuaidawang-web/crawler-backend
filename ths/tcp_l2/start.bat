@echo off
chcp 65001 >nul
echo ============================================
echo   同花顺 L2 实时数据推送 - 启动脚本
echo ============================================
echo.

REM 检查 Redis
docker ps | findstr redis-l2 >nul
if errorlevel 1 (
    echo [启动 Redis]...
    docker-compose up -d
    timeout /t 3 /nobreak >nul
) else (
    echo [OK] Redis 已运行
)

REM 检查 pool
redis-cli EXISTS ths:l2:pool >nul
if errorlevel 1 (
    echo [提示] 股票池为空，请运行:
    echo   redis-cli SADD ths:l2:pool 002384 600519 000001 300750 600667
)

echo.
echo [启动推送]...
cd src
python ths_l2_push.py
