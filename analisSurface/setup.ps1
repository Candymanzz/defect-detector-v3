# Одноразовый сетап Python-окружения для analisSurface.
#   .\analisSurface\setup.ps1
#   .\analisSurface\setup.ps1 -Recreate

param(
    [switch]$Recreate
)

$ErrorActionPreference = "Stop"
$Backend = Join-Path $PSScriptRoot "backend"
$Venv = Join-Path $Backend ".venv"
$VenvScripts = Join-Path $Venv "Scripts"
$PythonExe = Join-Path $VenvScripts "python.exe"
$SystemPythonDir = "C:\Program Files\Python314"
$SystemPythonScripts = Join-Path $SystemPythonDir "Scripts"

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

if (-not (Get-Command python -ErrorAction SilentlyContinue)) {
    throw "python не найден. Установите Python 3.10+ и отметьте Add python.exe to PATH."
}

if ($Recreate -and (Test-Path $Venv)) {
    Write-Host "==> Удаляю старый venv" -ForegroundColor Cyan
    Remove-Item -Recurse -Force $Venv
}

if (-not (Test-Path $PythonExe)) {
    Write-Host "==> Создаю venv: $Venv" -ForegroundColor Cyan
    python -m venv $Venv
}

Write-Host "==> pip install (requirements + dev)" -ForegroundColor Cyan
& $PythonExe -m pip install --upgrade pip
& $PythonExe -m pip install -r (Join-Path $Backend "requirements.txt")
& $PythonExe -m pip install -r (Join-Path $Backend "requirements-dev.txt")
if ($LASTEXITCODE -ne 0) { throw "pip install failed: $LASTEXITCODE" }

Add-UserPathEntries @(
    $VenvScripts,
    $SystemPythonDir,
    $SystemPythonScripts
)

Write-Host "`nГотово. В новом терминале: python, pip, uvicorn" -ForegroundColor Green
Write-Host "Запуск API: .\analisSurface\run.ps1"
Write-Host "Health: http://127.0.0.1:8000/health"
Write-Host "Если Cursor не видит python — перезапустите Cursor."
