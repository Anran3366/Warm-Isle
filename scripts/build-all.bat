@echo off
chcp 65001 >nul
setlocal
cd /d "%~dp0.."

set "NODE_DIR=E:\tools\node-v20.19.5-win-x64"
set "MVN=E:\tools\apache-maven-3.9.16\bin\mvn.cmd"
set "JAVA_HOME=E:\tools\jdk-21.0.12.1+1"
set "PATH=%JAVA_HOME%\bin;%NODE_DIR%;%PATH%"
REM esbuild 在系统 TEMP 下偶尔没有删除权限，改用项目内的临时目录
set "TMPDIR=%CD%\.tmp"
if not exist "%TMPDIR%" mkdir "%TMPDIR%"
set "TEMP=%TMPDIR%"
set "TMP=%TMPDIR%"

echo [1/3] 构建前端...
pushd web
if not exist node_modules (
  call npm ci --registry=https://registry.npmmirror.com --maxsockets=4 --no-fund --no-audit
  if errorlevel 1 (popd & echo 前端依赖安装失败 & exit /b 1)
)
call npm run build
if errorlevel 1 (popd & echo 前端构建失败 & exit /b 1)
popd

echo [2/3] 复制产物到 static...
if exist "src\main\resources\static" rmdir /s /q "src\main\resources\static"
mkdir "src\main\resources\static"
xcopy /e /i /y "web\dist\*" "src\main\resources\static\" >nul

echo [3/3] 打包后端...
call "%MVN%" -B -q -DskipTests package
if errorlevel 1 (echo 后端打包失败 & exit /b 1)

echo 完成: target\qqbot.jar 已包含管理后台
exit /b 0