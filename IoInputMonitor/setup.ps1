# Сборка IoInputMonitor (.NET 10) и PATH для dotnet / IoInputMonitor.
#   .\IoInputMonitor\setup.ps1
#   .\IoInputMonitor\setup.ps1 -Recreate

param(
    [switch]$Recreate
)

$ErrorActionPreference = "Stop"
$ProjectDir = $PSScriptRoot
$Csproj = Join-Path $ProjectDir "IoInputMonitor.csproj"
$TestCsproj = Join-Path $ProjectDir "IoInputMonitor.Tests\IoInputMonitor.Tests.csproj"
$OutDir = Join-Path $ProjectDir "bin\Release\net10.0"
$DotnetDir = "C:\Program Files\dotnet"
$DotnetTools = Join-Path $env:USERPROFILE ".dotnet\tools"

function Add-UserPathEntries([string[]]$Directories) {
    $userPath = [Environment]::GetEnvironmentVariable("Path", "User")
    $parts = @()
    if (-not [string]::IsNullOrWhiteSpace($userPath)) {
        $parts = @($userPath -split ';' | Where-Object { $_.Trim() -ne "" })
    }
    $known = @{}
    foreach ($part in $parts) {
        $known[$part.TrimEnd('\').ToLowerInvariant()] = $true
    }

    $prepend = @()
    foreach ($dir in $Directories) {
        if (-not $dir -or -not (Test-Path $dir)) { continue }
        $key = $dir.TrimEnd('\').ToLowerInvariant()
        if ($known.ContainsKey($key)) { continue }
        $prepend += $dir
        $known[$key] = $true
    }
    if ($prepend.Count -eq 0) { return }

    $newUserPath = ($prepend + $parts) -join ';'
    [Environment]::SetEnvironmentVariable("Path", $newUserPath, "User")
    $env:Path = ($prepend -join ';') + ';' + $env:Path

    if (-not ("Win32EnvBroadcast" -as [type])) {
        Add-Type @"
using System;
using System.Runtime.InteropServices;
public class Win32EnvBroadcast {
    [DllImport("user32.dll", SetLastError = true, CharSet = CharSet.Unicode)]
    public static extern IntPtr SendMessageTimeout(IntPtr hWnd, uint Msg, UIntPtr wParam, string lParam, uint fuFlags, uint uTimeout, out UIntPtr lpdwResult);
}
"@
    }
    $result = [UIntPtr]::Zero
    [Win32EnvBroadcast]::SendMessageTimeout([IntPtr]0xffff, 0x1A, [UIntPtr]::Zero, "Environment", 2, 5000, [ref]$result) | Out-Null
    Write-Host "==> PATH обновлён (User): $($prepend -join '; ')" -ForegroundColor Cyan
}

$env:Path = "$DotnetDir;$DotnetTools;$env:Path"

if (-not (Get-Command dotnet -ErrorAction SilentlyContinue)) {
    throw "dotnet не найден. Установите .NET SDK 10 в $DotnetDir"
}

Write-Host "==> dotnet $(dotnet --version)" -ForegroundColor Cyan

if ($Recreate) {
    Write-Host "==> dotnet clean" -ForegroundColor Cyan
    foreach ($dir in @("bin", "obj", "IoInputMonitor.Tests\bin", "IoInputMonitor.Tests\obj")) {
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

$exe = Join-Path $OutDir "IoInputMonitor.exe"
if (-not (Test-Path $exe)) { throw "Нет $exe" }

Write-Host "`nГотово. В новом терминале: dotnet, IoInputMonitor" -ForegroundColor Green
Write-Host "Запуск: .\IoInputMonitor\run.ps1"
Write-Host "COM-порты: .\IoInputMonitor\run.ps1 -List"
Write-Host "Если Cursor не видит dotnet — перезапустите Cursor."
