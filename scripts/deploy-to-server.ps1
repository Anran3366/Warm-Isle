# Windows 一键部署到云服务器（构建 → 上传 jar → 重启容器）
# 用法：
#   powershell -ExecutionPolicy Bypass -File scripts\deploy-to-server.ps1 -Server user@1.2.3.4
#   powershell ... -SkipBuild        # 跳过构建，直接用现有 target\qqbot.jar

param(
    [Parameter(Mandatory = $true)]
    [string]$Server,

    [string]$RemoteRoot = "/srv/qqbot",

    [switch]$SkipBuild
)

$ErrorActionPreference = "Stop"
$Root = Split-Path -Parent $PSScriptRoot

Write-Host "==> 项目根目录: $Root"

if (-not $SkipBuild) {
    Write-Host "==> [1/3] 构建（前端 + 后端）"
    & "$PSScriptRoot\build-all.bat"
    if ($LASTEXITCODE -ne 0) { throw "构建失败，中止部署" }
} else {
    Write-Host "==> [1/3] 跳过构建"
}

$Jar = Join-Path $Root "target\qqbot.jar"
if (-not (Test-Path $Jar)) { throw "找不到 $Jar，请先构建" }
$size = [math]::Round((Get-Item $Jar).Length / 1MB, 1)
Write-Host "==> [2/3] 上传 qqbot.jar ($size MB) 到 ${Server}:${RemoteRoot}/target/"

ssh $Server "mkdir -p ${RemoteRoot}/target"
scp $Jar "${Server}:${RemoteRoot}/target/qqbot.jar"
if ($LASTEXITCODE -ne 0) { throw "上传失败" }

Write-Host "==> [3/3] 重启容器"
ssh $Server "cd ${RemoteRoot}/deploy/linux && docker compose up -d --build qqbot"
if ($LASTEXITCODE -ne 0) { throw "远程重启失败" }

Write-Host "==> 完成，访问 http://<服务器IP>:8080（或你的域名）"
