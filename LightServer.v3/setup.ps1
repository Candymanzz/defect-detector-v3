# Сборка LightServer.v3 (.NET 10) и PATH для dotnet / LightServer.
#   .\LightServer.v3\setup.ps1
#   .\LightServer.v3\setup.ps1 -Recreate

param([switch]$Recreate)

$ErrorActionPreference = "Stop"
. (Join-Path (Split-Path -Parent $PSScriptRoot) "scripts\windows-env.ps1")

$ProjectDir = $PSScriptRoot
$Csproj = Join-Path $ProjectDir "LightServer.csproj"
$TestCsproj = Join-Path $ProjectDir "LightServer.Tests\LightServer.Tests.csproj"
$OutDir = Join-Path $ProjectDir "bin\Release\net10.0"
$DotnetDir = "C:\Program Files\dotnet"
$DotnetTools = Join-Path $env:USERPROFILE ".dotnet\tools"

$env:Path = "$DotnetDir;$DotnetTools;$env:Path"
if (-not (Get-Command dotnet -ErrorAction SilentlyContinue)) {
    throw "dotnet не найден. Установите .NET SDK 10 в $DotnetDir"
}

Write-Host "==> dotnet $(dotnet --version)" -ForegroundColor Cyan

if ($Recreate) {
    Write-Host "==> clean" -ForegroundColor Cyan
    foreach ($dir in @("bin", "obj", "LightServer.Tests\bin", "LightServer.Tests\obj")) {
        $full = Join-Path $ProjectDir $dir
        if (Test-Path $full) { Remove-Item -Recurse -Force $full }
    }
}

Set-Location $ProjectDir
Write-Host "==> dotnet restore" -ForegroundColor Cyan
dotnet restore $TestCsproj
if ($LASTEXITCODE -ne 0) { throw "dotnet restore failed: $LASTEXITCODE" }

Write-Host "==> dotnet build -c Release" -ForegroundColor Cyan
dotnet build $Csproj -c Release --no-restore
if ($LASTEXITCODE -ne 0) { throw "dotnet build failed: $LASTEXITCODE" }

Write-Host "==> dotnet test -c Release" -ForegroundColor Cyan
dotnet test $TestCsproj -c Release --no-restore
if ($LASTEXITCODE -ne 0) { throw "dotnet test failed: $LASTEXITCODE" }

Add-UserPathEntries @(
    $OutDir,
    $DotnetDir,
    $DotnetTools
)

$dll = Join-Path $OutDir "LightServer.dll"
if (-not (Test-Path $dll)) { throw "Нет $dll" }

Write-Host "`nГотово. Запуск: .\LightServer.v3\run.ps1" -ForegroundColor Green
Write-Host "HTTP: http://127.0.0.1:5080/swagger"
Write-Host "Если Cursor не видит dotnet — перезапустите Cursor."
