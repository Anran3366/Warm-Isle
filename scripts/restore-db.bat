@echo off
chcp 65001 >nul
setlocal

REM 用法: scripts\restore-db.bat "E:\backup\qqbot\qqbot-20261022-213703.db"
if "%~1"=="" (
  echo 用法: scripts\restore-db.bat ^<备份文件路径^>
  exit /b 1
)
if not exist "%~1" (
  echo [错误] 备份文件不存在: %~1
  exit /b 1
)

set "NSSM=E:\tools\nssm-2.24\win64\nssm.exe"
set "DB=E:\qqbot\data\qqbot.db"

echo 正在停止机器人服务...
"%NSSM%" stop qqbot >nul 2>&1
ping -n 4 127.0.0.1 >nul

echo 先保存当前数据库...
if exist "%DB%" copy /y "%DB%" "%DB%.before-restore" >nul

echo 覆盖数据库...
copy /y "%~1" "%DB%" >nul
del /q "%DB%-wal" "%DB%-shm" >nul 2>&1

echo 启动服务...
"%NSSM%" start qqbot >nul

echo 完成。当前数据已被替换为备份内容。
exit /b 0