@echo off
chcp 65001 >nul
echo ============================================
echo   同花顺 L2 实时数据推送 - 停止
echo ============================================
echo.

tasklist | findstr "python" | findstr "ths_l2_push" >nul
if errorlevel 1 (
    echo 推送程序未运行
) else (
    echo [停止推送]...
    taskkill /FI "WINDOWTITLE eq ths_l2_push*" /F 2>nul
    for /f "tokens=2" %%a in ('tasklist ^| findstr "python" ^| findstr "ths_l2_push"') do (
        taskkill /PID %%a /F
    )
    echo [OK] 已停止
)

echo.
pause
