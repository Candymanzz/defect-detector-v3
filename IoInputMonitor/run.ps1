# Запуск IoInputMonitor
#   .\IoInputMonitor\run.ps1
#   .\IoInputMonitor\run.ps1 -List
#   .\IoInputMonitor\run.ps1 -Probe
#   .\IoInputMonitor\run.ps1 -Config "config.exemple\blocks\52-io-input.yaml"

param(
    [switch]$List,
    [switch]$Probe,
    [string]$Config = "",
    [string[]]$ExtraArgs = @()
)

$ErrorActionPreference = "Stop"
$Root = Split-Path -Parent $PSScriptRoot
$OutDir = Join-Path $PSScriptRoot "bin\Release\net10.0"
$Exe = Join-Path $OutDir "IoInputMonitor.exe"
$DotnetDir = "C:\Program Files\dotnet"

if (-not (Test-Path $Exe)) {
    throw "Нет $Exe`nСначала: .\IoInputMonitor\setup.ps1"
}

if ([string]::IsNullOrWhiteSpace($Config)) {
    $preferred = Join-Path $Root "config\blocks\52-io-input.yaml"
    $fallback = Join-Path $Root "config.exemple\blocks\52-io-input.yaml"
    $Config = if (Test-Path $preferred) { $preferred } else { $fallback }
}

$ioArgs = @()
if (Test-Path $Config) {
    $ioArgs += "--io-config=$Config"
}
if ($List) { $ioArgs += "--list" }
elseif ($Probe) { $ioArgs += "--probe" }
$ioArgs += $ExtraArgs

$env:Path = "$OutDir;$DotnetDir;$env:Path"
Write-Host "IoInputMonitor → $Config" -ForegroundColor Green
Set-Location $Root
IoInputMonitor @ioArgs
