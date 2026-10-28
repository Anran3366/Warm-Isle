# -*- coding: utf-8 -*-
# QQ 陪聊机器人 - Windows 环境一键准备
# 用法：双击 setup.bat（会自动请求管理员权限）
# 兼容 Windows PowerShell 5.1

param(
    [switch]$NoInstall
)

$ErrorActionPreference = 'Continue'
$ProgressPreference = 'SilentlyContinue'

# ---------- 报告 ----------
$desktop = [Environment]::GetFolderPath('Desktop')
if ([string]::IsNullOrWhiteSpace($desktop)) { $desktop = $env:USERPROFILE }
$reportPath = Join-Path $desktop 'env-report.txt'
$script:lines = New-Object System.Collections.Generic.List[string]

function Say($msg) {
    Write-Host $msg
    $script:lines.Add([string]$msg)
}

function Section($title) {
    Say ''
    Say ('===== ' + $title + ' =====')
}

function Refresh-Path {
    $machine = [Environment]::GetEnvironmentVariable('Path', 'Machine')
    $user = [Environment]::GetEnvironmentVariable('Path', 'User')
    $env:Path = $machine + ';' + $user
}

function Get-Version-Line($exe, $verArgs) {
    $cmd = Get-Command $exe -ErrorAction SilentlyContinue
    if ($null -eq $cmd) { return $null }
    try {
        $out = & $exe $verArgs 2>&1
        return (($out | Select-Object -First 2) -join ' | ')
    } catch {
        return 'command failed: ' + $_.Exception.Message
    }
}

function Ensure-Package($id, $displayName, $probeCmd) {
    $existing = Get-Command $probeCmd -ErrorAction SilentlyContinue
    if ($null -ne $existing) {
        Say ('  [已有] ' + $displayName + ' -> ' + $existing.Source)
        return
    }
    if ($NoInstall) {
        Say ('  [缺失] ' + $displayName + '（已禁用安装，请手动安装）')
        return
    }
    Say ('  [安装] ' + $displayName + '（' + $id + '）...')
    $out = & winget install --id $id -e --silent --accept-package-agreements --accept-source-agreements 2>&1
    $code = $LASTEXITCODE
    if ($code -eq 0) {
        Say ('  [完成] ' + $displayName)
    } else {
        Say ('  [失败] ' + $displayName + '，winget 退出码 ' + $code)
        foreach ($l in ($out | Select-Object -Last 3)) { Say ('    ' + $l) }
    }
    Refresh-Path
}

# ---------- 开始 ----------
Clear-Host
Write-Host ''
Write-Host '  QQ 陪聊机器人 - Windows 环境准备' -ForegroundColor Cyan
Write-Host '  这个脚本会：检查/安装运行环境 -> 克隆代码 -> 生成报告' -ForegroundColor Gray
Write-Host ''
Write-Host '  预计耗时 5-15 分钟（取决于网络）' -ForegroundColor Gray
Write-Host '  按回车开始，输入 n 退出' -ForegroundColor Yellow
$go = Read-Host '  '
if ($go -eq 'n' -or $go -eq 'N') { exit }

$isAdmin = ([Security.Principal.WindowsPrincipal] [Security.Principal.WindowsIdentity]::GetCurrent()).IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)

Section '机器信息'
Say ('  时间      : ' + (Get-Date -Format 'yyyy-MM-dd HH:mm:ss'))
Say ('  主机名    : ' + $env:COMPUTERNAME)
Say ('  用户      : ' + $env:USERNAME)
Say ('  管理员    : ' + $isAdmin)
Say ('  PowerShell: ' + $PSVersionTable.PSVersion.ToString())
try {
    $os = Get-CimInstance Win32_OperatingSystem -ErrorAction Stop
    Say ('  系统      : ' + $os.Caption + ' ' + $os.Version + ' ' + $os.OSArchitecture)
} catch {
    Say '  系统      : 无法读取'
}

Section '磁盘与目录'
# 项目固定放在 E 盘；若脚本从仓库内运行，则以脚本所在目录为准（不依赖盘符假设）
$projectRoot = Split-Path -Parent $PSScriptRoot
$toolsRoot = 'E:\tools'
if (-not (Test-Path 'E:\')) {
    Say '  E 盘      : 不存在，改用 C:\Users\<用户>\qqbot'
    $projectRoot = Join-Path $env:USERPROFILE 'qqbot'
    $toolsRoot = Join-Path $env:USERPROFILE 'tools'
} else {
    Say '  E 盘      : 存在'
}
Say ('  项目目录  : ' + $projectRoot)
Say ('  工具目录  : ' + $toolsRoot)

Section 'winget'
$wg = Get-Command winget -ErrorAction SilentlyContinue
if ($null -eq $wg) {
    Say '  [缺失] winget 不可用，将无法自动安装依赖'
    Say '         请在 Microsoft Store 里搜索 “应用安装程序” 更新'
    $NoInstall = $true
} else {
    Say ('  [已有] ' + $wg.Source)
}

Section '安装依赖'
Ensure-Package 'Git.Git' 'Git' 'git'
Ensure-Package 'EclipseAdoptium.Temurin.21.JDK' 'JDK 21 (Temurin)' 'java'
Ensure-Package 'Apache.Maven' 'Maven' 'mvn'
Ensure-Package 'OpenJS.NodeJS.LTS' 'Node.js LTS' 'node'
Ensure-Package 'Gyan.FFmpeg' 'ffmpeg' 'ffmpeg'

Refresh-Path

Section '版本检查'
$checks = @(
    @{ Name = 'Git';      Cmd = 'git';    Args = '--version' },
    @{ Name = 'Java';     Cmd = 'java';   Args = '-version' },
    @{ Name = 'Maven';    Cmd = 'mvn';    Args = '-v' },
    @{ Name = 'Node';     Cmd = 'node';   Args = '-v' },
    @{ Name = 'npm';      Cmd = 'npm';    Args = '-v' },
    @{ Name = 'ffmpeg';   Cmd = 'ffmpeg'; Args = '-version' }
)
foreach ($c in $checks) {
    $v = Get-Version-Line $c.Cmd $c.Args
    if ($null -eq $v) { Say ('  [缺失] ' + $c.Name) }
    else { Say ('  [OK] ' + $c.Name + ' : ' + $v) }
}
Say ('  JAVA_HOME : ' + $(if ($env:JAVA_HOME) { $env:JAVA_HOME } else { '(未设置)' }))

Section '代码仓库'
if (-not (Test-Path $projectRoot)) {
    Say ('  目录不存在，正在克隆到 ' + $projectRoot + ' ...')
    $parent = Split-Path $projectRoot -Parent
    if (-not (Test-Path $parent)) { New-Item -ItemType Directory -Force -Path $parent | Out-Null }
    $cloneOut = & git clone https://gitee.com/menran/warm_isle.git $projectRoot 2>&1
    foreach ($l in $cloneOut) { Say ('    ' + $l) }
} else {
    Say ('  目录已存在: ' + $projectRoot)
}

if (Test-Path (Join-Path $projectRoot '.git')) {
    Push-Location $projectRoot
    Say ('  remote : ' + ((git remote get-url origin) -join ''))
    Say ('  branch : ' + ((git branch --show-current) -join ''))
    Say ('  commit : ' + ((git log --oneline -1) -join ''))
    Pop-Location
} else {
    Say '  [警告] 没有检测到 .git，克隆可能失败（可能需要 Gitee 账号密码）'
}

Section '端口占用'
foreach ($p in @(8080, 3001, 5180)) {
    $conn = Get-NetTCPConnection -LocalPort $p -State Listen -ErrorAction SilentlyContinue
    if ($null -ne $conn -and $conn.Count -gt 0) {
        $procId = $conn[0].OwningProcess
        $pname = ''
        try { $pname = (Get-Process -Id $procId -ErrorAction SilentlyContinue).ProcessName } catch { }
        Say ('  端口 ' + $p + ' : 被占用 (PID ' + $procId + ' ' + $pname + ')')
    } else {
        Say ('  端口 ' + $p + ' : 空闲')
    }
}

Section '可选组件'
if (Test-Path (Join-Path $toolsRoot 'napcat')) { Say ('  NapCat : 已放在 ' + (Join-Path $toolsRoot 'napcat')) }
else { Say '  NapCat : 未检测到（稍后手动安装，路径建议 E:\tools\napcat）' }
Say ('  ffmpeg in tools : ' + (Test-Path (Join-Path $toolsRoot 'ffmpeg.exe')))

Section '下一步'
Say '  1. 把本报告（桌面 env-report.txt）发给开发方'
Say '  2. 准备 DeepSeek API Key（platform.deepseek.com）'
Say '  3. 准备机器人小号 + 你的 QQ 号'
Say '  4. 安装 NapCat 并扫码登录小号，记下 OneBot 端口和 Token'

# ---------- 写报告 ----------
$lines | Out-File -FilePath $reportPath -Encoding UTF8
Say ''
Say ('报告已保存: ' + $reportPath)
Write-Host ''
Write-Host '  按任意键打开报告文件...' -ForegroundColor Yellow
$null = $Host.UI.RawUI.ReadKey('NoEcho,IncludeKeyDown')
Start-Process notepad.exe $reportPath
