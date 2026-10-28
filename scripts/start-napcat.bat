@echo off
chcp 65001 >nul
setlocal enabledelayedexpansion
title NapCat 启动器

REM NapCat 解压位置（默认 E:\tools\napcat）
set "NAPCAT_HOME=E:\tools\napcat\napcat"
set "WEBUI_PORT=6099"
set "WS_PORT=3001"

if not exist "%NAPCAT_HOME%\launcher.bat" (
  echo [错误] 找不到 NapCat: %NAPCAT_HOME%\launcher.bat
  echo        请确认 NapCat 解压在 E:\tools\napcat
  pause
  exit /b 1
)

REM 注入 QQ 进程需要管理员权限；没有就提权重开自己
net session >nul 2>&1
if errorlevel 1 (
  echo 正在请求管理员权限...
  powershell -NoProfile -Command "Start-Process -FilePath '%~f0' -Verb RunAs"
  exit /b
)

REM ---------- 读取 WebUI Token ----------
set "TOKEN="
for /f "usebackq delims=" %%t in (`powershell -NoProfile -Command "(Get-Content -LiteralPath '%NAPCAT_HOME%\config\webui.json' -Raw | ConvertFrom-Json).token"`) do set "TOKEN=%%t"

REM ---------- 已在运行：直接开 WebUI ----------
netstat -ano | findstr ":%WS_PORT% " | findstr "LISTENING" >nul
if not errorlevel 1 (
  echo [提示] NapCat 已在运行，直接打开 WebUI。
  call :open_webui
  ping -n 4 127.0.0.1 >nul
  exit /b 0
)

echo.
echo ============================================================
echo   正在启动 NapCat + QQ
echo   1) QQ 登录窗口会弹出，二维码图片也会自动打开
echo   2) 手机 QQ 扫码登录小号（2 分钟内有效）
echo   3) 登录成功后会自动打开 NapCat WebUI（免填 Token）
echo   二维码失效：关掉本窗口重新运行一次即可
echo ============================================================
echo.

del /q "%NAPCAT_HOME%\cache\qrcode.png" >nul 2>&1

start "NapCat 日志" cmd /k "%NAPCAT_HOME%\launcher.bat"

REM ---------- 等二维码生成并打开 ----------
set /a n=0
:wait_qr
if exist "%NAPCAT_HOME%\cache\qrcode.png" (
  echo [OK] 二维码已生成，正在打开图片，请扫码...
  start "" "%NAPCAT_HOME%\cache\qrcode.png"
  goto wait_login
)
set /a n+=1
if !n! geq 60 (
  echo [警告] 60 秒内没等到二维码，请看「NapCat 日志」窗口。
  goto wait_login
)
ping -n 2 127.0.0.1 >nul
goto wait_qr

REM ---------- 等扫码登录成功（3001 监听） ----------
:wait_login
set /a n=0
:wait_login_loop
netstat -ano | findstr ":%WS_PORT% " | findstr "LISTENING" >nul
if not errorlevel 1 (
  echo [OK] 登录成功，OneBot 服务已就绪。
  goto wait_webui
)
set /a n+=1
if !n! geq 180 (
  echo [警告] 6 分钟仍未登录成功，WebUI 仍会打开，可在里面重新出码。
  goto wait_webui
)
ping -n 2 127.0.0.1 >nul
goto wait_login_loop

REM ---------- 等 WebUI 端口就绪再开浏览器 ----------
:wait_webui
set /a n=0
:wait_webui_loop
netstat -ano | findstr ":%WEBUI_PORT% " | findstr "LISTENING" >nul
if not errorlevel 1 (
  call :open_webui
  goto done
)
set /a n+=1
if !n! geq 30 goto webui_timeout
ping -n 2 127.0.0.1 >nul
goto wait_webui_loop

:webui_timeout
echo [警告] 没等到 WebUI 端口 %WEBUI_PORT%，稍后可手动访问。
goto done

:done
echo.
echo ------------------------------------------------------------
echo   NapCat 运行在「NapCat 日志」窗口，关掉它才会停止 NapCat。
echo   本窗口可以关闭。
echo ------------------------------------------------------------
ping -n 11 127.0.0.1 >nul
exit /b 0

:open_webui
if defined TOKEN (
  echo [OK] 打开 WebUI: http://127.0.0.1:%WEBUI_PORT%/webui?token=%TOKEN%
  start "" "http://127.0.0.1:%WEBUI_PORT%/webui?token=%TOKEN%"
) else (
  echo [警告] 没读到 WebUI Token，打开首页，请手动填入。
  start "" "http://127.0.0.1:%WEBUI_PORT%/webui"
)
exit /b 0
