@echo off
chcp 65001 >nul
setlocal
title QQ Bot

cd /d "%~dp0.."

if not exist ".env" (
  echo [错误] 缺少 .env
  echo        请先执行: copy .env.example .env  然后填入 DEEPSEEK_API_KEY 等
  pause
  exit /b 1
)

netstat -ano | findstr ":8080 " | findstr "LISTENING" >nul
if not errorlevel 1 (
  echo [提示] 8080 已被占用，机器人可能已经在运行。
  echo        先执行 scripts\stop-bot.bat 再启动。
  pause
  exit /b 1
)

if not exist "target\qqbot.jar" (
  echo 未找到 target\qqbot.jar，开始构建...
  call mvn -B -DskipTests package
  if errorlevel 1 (
    echo [错误] 构建失败，请检查上面的 Maven 输出
    pause
    exit /b 1
  )
)

echo.
echo ============================================================
echo   启动机器人（Ctrl+C 停止）
echo   状态: http://127.0.0.1:8080/api/status
echo ============================================================
echo.

java -jar target\qqbot.jar

echo.
echo 机器人进程已退出。
pause
