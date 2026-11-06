# 立即触发云端部署并等待结果（不等 5 分钟的定时器）
# 用法：powershell -File scripts\deploy-now.ps1
#   或：.\scripts\deploy-now.ps1 -HostAlias qqbot
param(
    [string]$HostAlias = 'qqbot',
    [int]$TimeoutSeconds = 900
)

$ErrorActionPreference = 'Stop'

function Invoke-Remote([string]$cmd) {
    $out = & ssh $HostAlias $cmd 2>&1
    return (($out | Out-String).Trim())
}

Write-Host "[deploy] 触发 qqbot-autodeploy ..."
Invoke-Remote "sudo systemctl start qqbot-autodeploy" | Out-Null

$deadline = (Get-Date).AddSeconds($TimeoutSeconds)
$state = 'unknown'
while ((Get-Date) -lt $deadline) {
    Start-Sleep -Seconds 5
    $state = Invoke-Remote "systemctl is-active qqbot-autodeploy || true"
    if ($state -ne 'active' -and $state -ne 'activating') { break }
}

$head = Invoke-Remote "sudo git -C /srv/qqbot rev-parse --short HEAD"
$container = Invoke-Remote "sudo docker ps --filter name=^qqbot`$ --format '{{.Status}}'"

if ($state -eq 'failed' -or $state -eq 'unknown') {
    Write-Host "[deploy] 失败（state=$state），最近日志："
    Invoke-Remote "sudo journalctl -u qqbot-autodeploy -n 40 --no-pager"
    exit 1
}

Write-Host "[deploy] 已上线"
Write-Host "  commit : $head"
Write-Host "  qqbot  : $container"
