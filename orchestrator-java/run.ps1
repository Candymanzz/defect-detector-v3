# Запуск оркестратора
#   .\orchestrator-java\run.ps1
#   .\orchestrator-java\run.ps1 -Config "config.exemple\config.yaml"

param(
    [string]$Config = ""
)

$ErrorActionPreference = "Stop"
. (Join-Path (Split-Path -Parent $PSScriptRoot) "scripts\windows-env.ps1")

$Root = Split-Path -Parent $PSScriptRoot
$Jar = Join-Path $PSScriptRoot "target\orchestrator-0.1.0-SNAPSHOT.jar"
if (-not (Test-Path $Jar)) {
    throw "Нет $Jar`nСначала: .\orchestrator-java\setup.ps1"
}

if ([string]::IsNullOrWhiteSpace($Config)) {
    $preferred = Join-Path $Root "config\config.yaml"
    $fallback = Join-Path $Root "config.exemple\config.yaml"
    $Config = if (Test-Path $preferred) { $preferred } else { $fallback }
}
if (-not (Test-Path $Config)) {
    throw "Конфиг не найден: $Config"
}

$jdkHome = Resolve-JdkHome
$env:JAVA_HOME = $jdkHome
$env:Path = "$(Join-Path $jdkHome 'bin');$env:Path"

Write-Host "orchestrator → $Config" -ForegroundColor Green
Write-Host "API http://127.0.0.1:8099  WS ws://127.0.0.1:8765"
Set-Location $Root
java -jar $Jar $Config
