$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $MyInvocation.MyCommand.Path
$maven = Join-Path $projectRoot '.tools\apache-maven-3.9.9\bin\mvn.cmd'
if (-not (Test-Path -LiteralPath $maven)) {
    throw 'Maven local is missing. Install Maven or restore .tools/apache-maven-3.9.9.'
}
$env:STUDYROOM_DB_URL = if ($env:STUDYROOM_DB_URL) { $env:STUDYROOM_DB_URL } else { 'jdbc:postgresql://localhost:5432/studyroom' }
$env:STUDYROOM_DB_USER = if ($env:STUDYROOM_DB_USER) { $env:STUDYROOM_DB_USER } else { 'postgres' }
if (-not $env:STUDYROOM_DB_PASSWORD) {
    $securePassword = Read-Host 'PostgreSQL password for user postgres' -AsSecureString
    $env:STUDYROOM_DB_PASSWORD = [System.Net.NetworkCredential]::new('', $securePassword).Password
}
& $maven "-Dmaven.repo.local=$projectRoot\.tools\m2" javafx:run
