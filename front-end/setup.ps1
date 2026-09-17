# Одноразовый сетап front-end: Node PATH + npm install.
#   .\front-end\setup.ps1
#   .\front-end\setup.ps1 -Recreate

param(
    [switch]$Recreate
)

$ErrorActionPreference = "Stop"
$FrontEndDir = $PSScriptRoot
$NodeDir = "C:\Program Files\nodejs"
$NpmGlobal = Join-Path $env:APPDATA "npm"
$BinDir = Join-Path $FrontEndDir "node_modules\.bin"

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

$env:Path = "$NodeDir;$NpmGlobal;$env:Path"

if (-not (Get-Command node -ErrorAction SilentlyContinue)) {
    throw "node не найден. Установите Node.js 20+ в $NodeDir"
}
if (-not (Get-Command npm -ErrorAction SilentlyContinue)) {
    throw "npm не найден."
}

$nodeVer = node --version
Write-Host "==> $nodeVer / npm $(npm --version)" -ForegroundColor Cyan

if ($Recreate -and (Test-Path (Join-Path $FrontEndDir "node_modules"))) {
    Write-Host "==> Удаляю node_modules" -ForegroundColor Cyan
    Remove-Item -Recurse -Force (Join-Path $FrontEndDir "node_modules")
}

Set-Location $FrontEndDir
Write-Host "==> npm ci" -ForegroundColor Cyan
npm ci
if ($LASTEXITCODE -ne 0) {
    Write-Host "==> npm ci не прошёл, npm install" -ForegroundColor Yellow
    npm install
    if ($LASTEXITCODE -ne 0) { throw "npm install failed: $LASTEXITCODE" }
}

Write-Host "==> npm test" -ForegroundColor Cyan
npm test
if ($LASTEXITCODE -ne 0) { throw "npm test failed: $LASTEXITCODE" }

Add-UserPathEntries @(
    $BinDir,
    $NodeDir,
    $NpmGlobal
)

Write-Host "`nГотово. В новом терминале: node, npm, vite, electron" -ForegroundColor Green
Write-Host "Запуск UI: .\front-end\run.ps1"
Write-Host "Vite: http://localhost:5173"
Write-Host "Если Cursor не видит node — перезапустите Cursor."
