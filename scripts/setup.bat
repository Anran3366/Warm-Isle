@echo off
chcp 65001 >nul
setlocal

:: 需要管理员权限（winget 安装依赖时用到）
net session >nul 2>&1
if errorlevel 1 (
    echo 正在请求管理员权限...
    powershell -NoProfile -Command "Start-Process -FilePath '%~f0' -Verb RunAs"
    exit /b
)

powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0setup-windows.ps1"

echo.
pause
