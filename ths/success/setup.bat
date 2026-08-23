@echo off
chcp 65001 >nul
echo ========================================
echo   同花顺抓包环境 - 一键配置脚本
echo ========================================
echo.

:: 配置路径
set ADB=D:\leidian\LDPlayer9\adb.exe
set CERT_DIR=%USERPROFILE%\.mitmproxy
set HASH=c8750f0d

echo [1/6] 获取 ROOT 权限...
%ADB% -s emulator-5556 root
%ADB% -s emulator-5556 wait-for-device

echo [2/6] 推送证书到模拟器...
%ADB% -s emulator-5556 push "%CERT_DIR%\%HASH%.0" /data/local/tmp/%HASH%.0

echo [3/6] 备份系统证书并注入...
%ADB% -s emulator-5556 shell "mkdir -p /data/local/tmp/cacerts"
%ADB% -s emulator-5556 shell "cp /system/etc/security/cacerts/* /data/local/tmp/cacerts/"
%ADB% -s emulator-5556 shell "cp /data/local/tmp/%HASH%.0 /data/local/tmp/cacerts/"
%ADB% -s emulator-5556 shell "chmod 644 /data/local/tmp/cacerts/%HASH%.0"

echo [4/6] 绑定挂载系统证书目录...
%ADB% -s emulator-5556 shell "mount --bind /data/local/tmp/cacerts /system/etc/security/cacerts"

echo [5/6] 设置模拟器代理...
%ADB% -s emulator-5556 shell "settings put global http_proxy 192.168.3.27:8080"

echo [6/6] 验证...
%ADB% -s emulator-5556 shell "ls -la /system/etc/security/cacerts/%HASH%.0"
%ADB% -s emulator-5556 shell "settings get global http_proxy"

echo.
echo ========================================
echo   配置完成！
echo   启动 mitmproxy: mitmweb -p 8080 -s ths_capture.py --web-host 127.0.0.1 --web-port 8081
echo   可视化界面: http://127.0.0.1:8081
echo ========================================
pause
