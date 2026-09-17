# Запуск camera-worker
#   .\camera-worker\run.ps1
#   .\camera-worker\run.ps1 -CameraId 0
#   .\camera-worker\run.ps1 -Config "config\config.json"

param(
    [int]$CameraId = 0,
    [string]$Config = ""
)

$ErrorActionPreference = "Stop"
$Root = Split-Path -Parent $PSScriptRoot
$ReleaseDir = Join-Path $PSScriptRoot "build\Release"
$Exe = Join-Path $ReleaseDir "camera_worker.exe"

if (-not (Test-Path $Exe)) {
    throw "Нет $Exe`nСначала: .\camera-worker\setup.ps1"
}

if ([string]::IsNullOrWhiteSpace($Config)) {
    $preferred = Join-Path $Root "config\config.json"
    $fallback = Join-Path $Root "config.exemple\config.json"
    $Config = if (Test-Path $preferred) { $preferred } else { $fallback }
}
if (-not (Test-Path $Config)) {
    throw "Конфиг не найден: $Config"
}

$env:Path = "$ReleaseDir;$env:Path"
Write-Host "camera-worker → $Config  camera_id=$CameraId" -ForegroundColor Green
Set-Location $Root
camera_worker $Config $CameraId
