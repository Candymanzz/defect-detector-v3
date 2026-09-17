# Сборка orchestrator-java (Maven) и PATH для java/mvn.
#   .\orchestrator-java\setup.ps1
#   .\orchestrator-java\setup.ps1 -Recreate

param([switch]$Recreate)

$ErrorActionPreference = "Stop"
. (Join-Path (Split-Path -Parent $PSScriptRoot) "scripts\windows-env.ps1")

$ProjectDir = $PSScriptRoot
$Jar = Join-Path $ProjectDir "target\orchestrator-0.1.0-SNAPSHOT.jar"

Import-JavaBuildEnv | Out-Null

if ($Recreate -and (Test-Path (Join-Path $ProjectDir "target"))) {
    Write-Host "==> Удаляю target" -ForegroundColor Cyan
    Remove-Item -Recurse -Force (Join-Path $ProjectDir "target")
}

Set-Location $ProjectDir
Write-Host "==> mvn package" -ForegroundColor Cyan
mvn -B package
if ($LASTEXITCODE -ne 0) { throw "mvn package failed: $LASTEXITCODE" }

if (-not (Test-Path $Jar)) { throw "Нет $Jar" }

Write-Host "`nГотово. Запуск: .\orchestrator-java\run.ps1" -ForegroundColor Green
Write-Host "API: http://127.0.0.1:8099  WS: ws://127.0.0.1:8765"
Write-Host "Если Cursor не видит java/mvn — перезапустите Cursor."
