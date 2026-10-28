# 一键准备 Windows 开发环境（Git / JDK21 / Maven / Node20）
# 用法：powershell -ExecutionPolicy Bypass -File scripts\setup-windows-all.ps1

$ErrorActionPreference = 'Stop'
function Info($m) { Write-Host "[环境] $m" -ForegroundColor Green }
function Warn($m) { Write-Host "[提示] $m" -ForegroundColor Yellow }

if (-not (Get-Command winget -ErrorAction SilentlyContinue)) {
    Warn '没有找到 winget，请先在 Microsoft Store 安装「应用安装程序」'
    exit 1
}

$pkgs = @(
    @{ Id = 'Git.Git'; Name = 'Git' },
    @{ Id = 'Microsoft.OpenJDK.21'; Name = 'JDK 21' },
    @{ Id = 'Apache.Maven'; Name = 'Maven' },
    @{ Id = 'OpenJS.NodeJS.LTS'; Name = 'Node.js LTS' }
)

foreach ($p in $pkgs) {
    Info "安装 $($p.Name) ..."
    winget install --id $p.Id -e --accept-source-agreements --accept-package-agreements --silent
}

# 刷新 PATH（尽量）
$machine = [Environment]::GetEnvironmentVariable('Path', 'Machine')
$user = [Environment]::GetEnvironmentVariable('Path', 'User')
$env:Path = "$machine;$user"

# JDK
$jdk = Get-ChildItem 'C:\Program Files\Microsoft' -Directory -Filter 'jdk-21*' -ErrorAction SilentlyContinue | Select-Object -First 1
if ($jdk) {
    [Environment]::SetEnvironmentVariable('JAVA_HOME', $jdk.FullName, 'User')
    $env:JAVA_HOME = $jdk.FullName
    Info "JAVA_HOME=$($jdk.FullName)"
}

# Maven
$maven = Get-ChildItem 'C:\Program Files' -Directory -Filter 'apache-maven-*' -ErrorAction SilentlyContinue | Select-Object -First 1
if ($maven) {
    [Environment]::SetEnvironmentVariable('MAVEN_HOME', $maven.FullName, 'User')
    Info "MAVEN_HOME=$($maven.FullName)"
}

Info '检查版本：'
& git --version
& java -version
& mvn -v
& node -v

$target = 'E:\qqbot'
if (-not (Test-Path (Join-Path $target '.git'))) {
    Info "克隆代码到 $target ..."
    & git clone https://gitee.com/menran/warm_isle.git $target
} else {
    Info "代码已存在：$target"
}

Info '完成。'
Write-Host '后续：'
Write-Host '  cd E:\qqbot'
Write-Host '  mvn -DskipTests package'
Write-Host '  cd web && npm install && npm run dev'
