@echo off
chcp 65001 >nul
setlocal
set "PYTHONIOENCODING=utf-8"

set "PY=E:\tools\python311\python.exe"
if not exist "%PY%" (
  echo [错误] 找不到 Python: %PY%
  exit /b 1
)

set "MIRROR_ARG="
if exist "F:\" set "MIRROR_ARG=--mirror F:\backup\qqbot"

"%PY%" "%~dp0backup_db.py" %MIRROR_ARG% %*
if errorlevel 1 (
  echo [错误] 备份失败
  exit /b 1
)
exit /b 0