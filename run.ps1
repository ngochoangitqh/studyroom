$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $MyInvocation.MyCommand.Path
$maven = Join-Path $projectRoot '.tools\apache-maven-3.9.9\bin\mvn.cmd'
if (-not (Test-Path -LiteralPath $maven)) {
    throw 'Maven local is missing. Install Maven or restore .tools/apache-maven-3.9.9.'
}

# Auto-detect local Wi-Fi / LAN IP to display
$localIp = 'localhost'
try {
    $ipObj = Get-NetIPAddress -AddressFamily IPv4 -ErrorAction SilentlyContinue | 
             Where-Object { $_.InterfaceAlias -notmatch 'Loopback|vEthernet|Virtual|WSL' -and $_.IPAddress -notlike '127.*' -and $_.IPAddress -notlike '169.254.*' } | 
             Select-Object -First 1
    if ($ipObj) { $localIp = $ipObj.IPAddress }
} catch {}

Clear-Host
Write-Host "==========================================================" -ForegroundColor Cyan
Write-Host "               STUDYROOM - KET NOI HE THONG               " -ForegroundColor Yellow
Write-Host "==========================================================" -ForegroundColor Cyan
Write-Host ""

if (-not $env:STUDYROOM_DB_URL) {
    Write-Host "Chon che do khoi dong cho may tinh nay:" -ForegroundColor White
    Write-Host "  [1] MAY CHU (SERVER)  - May nay cai PostgreSQL, luu database" -ForegroundColor Green
    Write-Host "  [2] MAY CON (CLIENT)  - Ket noi toi May Chu cua ban be" -ForegroundColor Yellow
    Write-Host ""
    $mode = Read-Host "Ban chon [1] hay [2]? (Nhan Enter de chon mac dinh 1 - May Chu)"
    if (-not $mode) { $mode = "1" }

    if ($mode.Trim() -eq "2") {
        Write-Host ""
        Write-Host ">>> CHE DO: MAY CON (CLIENT)" -ForegroundColor Yellow
        $serverIp = Read-Host "Nhap dia chi IP cua May Chu (Vi du: 192.168.1.14)"
        if (-not $serverIp) { $serverIp = 'localhost' }
        $serverIp = $serverIp.Trim()
        $env:STUDYROOM_DB_URL = "jdbc:postgresql://${serverIp}:5432/studyroom"
        if ($serverIp -ne 'localhost' -and $serverIp -ne '127.0.0.1') {
            $env:STUDYROOM_PEER = "${serverIp}:5050"
        }
        Write-Host "-> Dang ket noi toi May Chu tai: $serverIp..." -ForegroundColor Cyan
    } else {
        Write-Host ""
        Write-Host ">>> CHE DO: MAY CHU (SERVER)" -ForegroundColor Green
        Write-Host "-> Dia chi IP cua may nay la: $localIp" -ForegroundColor Magenta
        Write-Host "-> Hay gui IP nay [$localIp] cho ban be de ho ket noi vao!" -ForegroundColor Yellow
        $env:STUDYROOM_DB_URL = "jdbc:postgresql://localhost:5432/studyroom"
    }
} else {
    Write-Host "STUDYROOM_DB_URL da duoc thiet lap: $env:STUDYROOM_DB_URL" -ForegroundColor Cyan
}

$env:STUDYROOM_DB_USER = if ($env:STUDYROOM_DB_USER) { $env:STUDYROOM_DB_USER } else { 'postgres' }
if (-not $env:STUDYROOM_DB_PASSWORD) {
    $env:STUDYROOM_DB_PASSWORD = 'ngochoang'
}

Write-Host ""
Write-Host "Dang khoi dong Studyroom, vui long cho giay lat..." -ForegroundColor Green
Write-Host "==========================================================" -ForegroundColor Cyan

$peerParam = if ($env:STUDYROOM_PEER) { "-Dstudyroom.peer=$env:STUDYROOM_PEER" } else { "" }
& $maven "-Dmaven.repo.local=$projectRoot\.tools\m2" $peerParam javafx:run
