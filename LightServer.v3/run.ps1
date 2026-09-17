# Запуск LightServer.v3
#   .\LightServer.v3\run.ps1
#   .\LightServer.v3\run.ps1 -Config "config.exemple\blocks\51-light-hardware.yaml"

param(
    [string]$Urls = "http://127.0.0.1:5080",
    [string]$Config = ""
)

$ErrorActionPreference = "Stop"
$Root = Split-Path -Parent $PSScriptRoot
$OutDir = Join-Path $PSScriptRoot "bin\Release\net10.0"
$Dll = Join-Path $OutDir "LightServer.dll"
$DotnetDir = "C:\Program Files\dotnet"

if (-not (Test-Path $Dll)) {
    throw "Нет $Dll`nСначала: .\LightServer.v3\setup.ps1"
}

if ([string]::IsNullOrWhiteSpace($Config)) {
    $preferred = Join-Path $Root "config\blocks\51-light-hardware.yaml"
    $fallback = Join-Path $Root "config.exemple\blocks\51-light-hardware.yaml"
    $Config = if (Test-Path $preferred) { $preferred } else { $fallback }
}

$lsArgs = @("exec", $Dll, "--urls", $Urls)
if (Test-Path $Config) {
    $lsArgs += "--light-config=$Config"
}

$env:Path = "$OutDir;$DotnetDir;$env:Path"
Write-Host "LightServer → $Urls" -ForegroundColor Green
Set-Location $Root
dotnet @lsArgs
