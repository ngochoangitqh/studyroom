$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $MyInvocation.MyCommand.Path
$maven = Join-Path $projectRoot '.tools\apache-maven-3.9.9\bin\mvn.cmd'
if (-not (Test-Path -LiteralPath $maven)) {
    throw 'Maven local is missing. Install Maven or restore .tools/apache-maven-3.9.9.'
}
if (-not $env:STUDYROOM_DB_URL) {
    $dbHost = Read-Host 'Nhap IP may chu Database (Nhan Enter de dung localhost)'
    if (-not $dbHost) { $dbHost = 'localhost' }
    $env:STUDYROOM_DB_URL = "jdbc:postgresql://${dbHost}:5432/studyroom"
    if ($dbHost -ne 'localhost' -and $dbHost -ne '127.0.0.1') {
        $env:STUDYROOM_PEER = "${dbHost}:5050"
    }
}
$env:STUDYROOM_DB_USER = if ($env:STUDYROOM_DB_USER) { $env:STUDYROOM_DB_USER } else { 'postgres' }
if (-not $env:STUDYROOM_DB_PASSWORD) {
    $securePassword = Read-Host 'PostgreSQL password for user postgres' -AsSecureString
    $env:STUDYROOM_DB_PASSWORD = [System.Net.NetworkCredential]::new('', $securePassword).Password
}
$peerParam = if ($env:STUDYROOM_PEER) { "-Dstudyroom.peer=$env:STUDYROOM_PEER" } else { "" }
& $maven "-Dmaven.repo.local=$projectRoot\.tools\m2" $peerParam javafx:run
