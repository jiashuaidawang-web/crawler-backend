@echo off
chcp 65001 >nul
set ADB=D:\leidian\LDPlayer9\adb.exe
set OUT=C:\Users\Administrator\Desktop

echo ========================================
echo   同花顺 TCP 私有协议抓包脚本
echo ========================================
echo.
echo   使用说明:
echo   1. 先在模拟器中打开同花顺
echo   2. 进入任意股票的十档盘口页面
echo   3. 运行此脚本后，疯狂刷新盘口 30 秒
echo   4. 抓包结束后自动导出 pcap 文件
echo.

set /p CONFIRM="按 Enter 开始抓包..."

echo.
echo [1/5] 清理旧 pcap...
%ADB% -s emulator-5556 shell "rm -f /data/local/tmp/ths_l2.pcap"

echo [2/5] 启动 tcpdump 后台抓包...
start /B cmd /c "%ADB% -s emulator-5556 shell "tcpdump -i wlan0 -w /data/local/tmp/ths_l2.pcap tcp port 9528 or tcp port 8887""

echo [3/5] 抓包中... 请在模拟器疯狂刷新十档盘口！
echo       剩余时间: 30 秒
timeout /t 30 /nobreak >nul

echo [4/5] 停止抓包...
%ADB% -s emulator-5556 shell "pkill tcpdump"
timeout /t 2 /nobreak >nul

echo [5/5] 导出 pcap 到桌面...
%ADB% -s emulator-5556 pull /data/local/tmp/ths_l2.pcap %OUT%\ths_l2.pcap

echo.
echo ========================================
echo   抓包完成！
echo   文件位置: %OUT%\ths_l2.pcap
echo   请用 Wireshark 打开分析
echo ========================================

:: 自动打开（如果 Wireshark 已安装）
if exist "C:\Program Files\Wireshark\Wireshark.exe" (
    start "" "C:\Program Files\Wireshark\Wireshark.exe" "%OUT%\ths_l2.pcap"
)

pause
