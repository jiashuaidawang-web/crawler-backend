@echo off
chcp 65001 >nul
echo ============================================
echo   同花顺 L2 实时数据推送 - 状态检查
echo ============================================
echo.

echo [Redis 状态]
docker ps | findstr redis-l2
echo.

echo [Meta 数据]
redis-cli HGETALL ths:l2:meta
echo.

echo [股票池]
redis-cli SMEMBERS ths:l2:pool
echo.

echo [各股票数据]
for %%c in (000001 002384 300750 600519 600667) do (
    echo   %%c:
    redis-cli XLEN ths:l2:tick:%%c
    redis-cli EXISTS ths:l2:quote:%%c
)
echo.

echo [最近事件]
redis-cli XRANGE ths:l2:events - + COUNT 3
echo.
