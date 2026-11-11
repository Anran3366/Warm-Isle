@echo off
chcp 65001 >nul
setlocal enabledelayedexpansion
title QQ Bot - 环境自检

set FAILED=0

echo ============================================================
echo    QQ 陪聊机器人 - 环境自检
echo    时间: %date% %time%
echo ============================================================
echo.

call :section "1/9 JDK"
where java >nul 2>nul
if errorlevel 1 (
  echo   [FAIL] 找不到 java
  echo          请安装 JDK 21 Temurin 并加入 PATH
  set FAILED=1
) else (
  for /f "tokens=3" %%v in ('java -version 2^>^&1 ^| findstr /i "version"') do set JVER=%%v
  set JVER=!JVER:"=!
  for /f "tokens=1 delims=." %%a in ("!JVER!") do set JMAJOR=%%a
  if !JMAJOR! LSS 21 (
    echo   [FAIL] 需要 JDK 21+，当前 !JVER!
    set FAILED=1
  ) else (
    echo   [ OK ] java !JVER!
  )
)

call :section "2/9 Maven"
where mvn >nul 2>nul
if errorlevel 1 (
  echo   [FAIL] 找不到 mvn
  set FAILED=1
) else (
  for /f "tokens=3" %%v in ('mvn -v 2^>^&1 ^| findstr /i "Apache Maven"') do echo   [ OK ] Maven %%v
)

call :section "3/9 Node.js / npm"
where node >nul 2>nul
if errorlevel 1 (
  echo   [FAIL] 找不到 node
  set FAILED=1
) else (
  for /f %%v in ('node -v') do echo   [ OK ] node %%v
)
where npm >nul 2>nul
if errorlevel 1 (echo   [FAIL] 找不到 npm & set FAILED=1) else (for /f %%v in ('npm -v') do echo   [ OK ] npm %%v)

call :section "4/9 Git"
where git >nul 2>nul
if errorlevel 1 (echo   [FAIL] 找不到 git & set FAILED=1) else (for /f "tokens=3" %%v in ('git --version') do echo   [ OK ] git %%v)

call :section "5/9 ffmpeg"
where ffmpeg >nul 2>nul
if errorlevel 1 (
  echo   [WARN] PATH 里没有 ffmpeg
  echo          可放到 tools\ffmpeg.exe，程序会优先用本地版本
) else (
  for /f "tokens=3" %%v in ('ffmpeg -version 2^>^&1 ^| findstr /i "ffmpeg version"') do echo   [ OK ] ffmpeg %%v
)

call :section "6/9 NSSM"
where nssm >nul 2>nul
if errorlevel 1 (
  echo   [WARN] PATH 里没有 nssm（仅注册服务时需要）
) else (
  echo   [ OK ] nssm 已找到
)

call :section "7/9 本地工具 (tools)"
if exist tools\ffmpeg.exe (echo   [ OK ] tools\ffmpeg.exe) else (echo   [WARN] 缺少 tools\ffmpeg.exe)
if exist tools\silk_v3_encoder.exe (echo   [ OK ] tools\silk_v3_encoder.exe) else (echo   [WARN] 缺少 tools\silk_v3_encoder.exe - 语音功能需要)

call :section "8/9 端口占用"
netstat -ano | findstr ":3001 " >nul && (echo   [WARN] 3001 已被占用 NapCat OneBot - 若 NapCat 正在跑属正常) || (echo   [ OK ] 3001 空闲)
netstat -ano | findstr ":8080 " >nul && (echo   [WARN] 8080 已被占用 后台端口) || (echo   [ OK ] 8080 空闲)

call :section "9/9 目录结构"
for %%d in (src web docs scripts tools) do (
  if exist %%d (echo   [ OK ] %%d\) else (echo   [INFO] %%d\ 尚未创建 - 当前阶段可忽略)
)

echo.
echo ============================================================
if "%FAILED%"=="1" (
  echo   结果: 有必需项未通过，请按上面 [FAIL] 提示补装
) else (
  echo   结果: 必需项全部通过
)
echo ============================================================
echo.
echo 请把以上完整输出复制发给开发方。
echo.
pause
endlocal
exit /b 0

:section
echo.
echo --- %~1 ----------------------------------------------
goto :eof
