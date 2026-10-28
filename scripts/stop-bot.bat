@echo off
chcp 65001 >nul
setlocal enabledelayedexpansion

set "PID="
for /f "tokens=5" %%p in ('netstat -ano ^| findstr ":8080 " ^| findstr "LISTENING"') do set "PID=%%p"

if not defined PID (
  echo [提示] 8080 没有监听进程，机器人未在运行。
  timeout /t 2 >nul
  exit /b 0
)

echo 正在结束 PID !PID! ...
taskkill /PID !PID! /F >nul 2>&1
if errorlevel 1 (
  echo [错误] 结束失败，可能需要管理员权限。
  pause
  exit /b 1
)

echo 已停止。
timeout /t 2 >nul
