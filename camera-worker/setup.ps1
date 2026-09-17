# Сборка camera-worker (CMake + MSVC) и PATH для cmake/cl/camera_worker.
#   .\camera-worker\setup.ps1
#   .\camera-worker\setup.ps1 -Recreate

param(
    [switch]$Recreate
)

$ErrorActionPreference = "Stop"
$Root = Split-Path -Parent $PSScriptRoot
$WorkerDir = $PSScriptRoot
$BuildDir = Join-Path $WorkerDir "build"
$ReleaseDir = Join-Path $BuildDir "Release"
$MvsDev = "C:\Program Files (x86)\MVS\Development"
$MvsRuntime = "C:\Program Files (x86)\Common Files\MVS\Runtime\Win64_x64"
$CMakeBin = "C:\Program Files\CMake\bin"
$Vswhere = Join-Path ${env:ProgramFiles(x86)} "Microsoft Visual Studio\Installer\vswhere.exe"

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

function Import-VcVars64 {
    param([string]$VsRoot)
    $vcvars = Join-Path $VsRoot "VC\Auxiliary\Build\vcvars64.bat"
    if (-not (Test-Path $vcvars)) { throw "vcvars64.bat не найден: $vcvars" }
    cmd /c "`"$vcvars`" >nul && set" | ForEach-Object {
        if ($_ -match '^([^=]+)=(.*)$') {
            Set-Item -Path "Env:$($matches[1])" -Value $matches[2]
        }
    }
}

if (-not (Test-Path $CMakeBin)) { throw "CMake не найден: $CMakeBin" }
if (-not (Test-Path $Vswhere)) { throw "vswhere не найден. Установите Visual Studio Build Tools." }

$vsRoot = (& $Vswhere -latest -products * -requires Microsoft.VisualStudio.Component.VC.Tools.x86.x64 -property installationPath | Select-Object -First 1)
if (-not $vsRoot) { throw "Visual Studio Build Tools (C++) не найдены." }
$msbuildDir = Join-Path $vsRoot "MSBuild\Current\Bin"
$cl = Get-ChildItem (Join-Path $vsRoot "VC\Tools\MSVC\*\bin\Hostx64\x64\cl.exe") -ErrorAction SilentlyContinue |
    Sort-Object FullName -Descending |
    Select-Object -First 1
$clDir = if ($cl) { $cl.DirectoryName } else { $null }

Write-Host "==> vcvars64 ($vsRoot)" -ForegroundColor Cyan
Import-VcVars64 $vsRoot
$toolPath = (@($CMakeBin, $msbuildDir, $clDir, $MvsRuntime) | Where-Object { $_ }) -join ';'
$env:Path = "$toolPath;$env:Path"

if ($Recreate -and (Test-Path $BuildDir)) {
    Write-Host "==> Удаляю $BuildDir" -ForegroundColor Cyan
    Remove-Item -Recurse -Force $BuildDir
} elseif (Test-Path (Join-Path $BuildDir "CMakeCache.txt")) {
    $cache = Get-Content (Join-Path $BuildDir "CMakeCache.txt") -Raw
    $expected = ($WorkerDir -replace '\\', '/')
    if ($cache -notmatch [regex]::Escape($expected)) {
        Write-Host "==> Кэш CMake от другого пути, пересоздаю build" -ForegroundColor Yellow
        Remove-Item -Recurse -Force $BuildDir
    }
}

$cmakeArgs = @("-S", $WorkerDir, "-B", $BuildDir)
if (Test-Path (Join-Path $MvsDev "Includes\MvCameraControl.h")) {
    $cmakeArgs += "-DMVS_ROOT=$($MvsDev -replace '\\','/')"
}

Write-Host "==> cmake configure" -ForegroundColor Cyan
cmake @cmakeArgs
if ($LASTEXITCODE -ne 0) { throw "cmake configure failed: $LASTEXITCODE" }

Write-Host "==> cmake build Release" -ForegroundColor Cyan
cmake --build $BuildDir --config Release
if ($LASTEXITCODE -ne 0) { throw "cmake build failed: $LASTEXITCODE" }

Write-Host "==> ctest" -ForegroundColor Cyan
ctest --test-dir $BuildDir -C Release --output-on-failure
if ($LASTEXITCODE -ne 0) { throw "ctest failed: $LASTEXITCODE" }

Add-UserPathEntries @(
    $ReleaseDir,
    $CMakeBin,
    $msbuildDir,
    $clDir,
    $MvsRuntime,
    (Split-Path $Vswhere)
)

$exe = Join-Path $ReleaseDir "camera_worker.exe"
if (-not (Test-Path $exe)) { throw "Нет $exe" }

Write-Host "`nГотово. В новом терминале: cmake, cl, camera_worker" -ForegroundColor Green
Write-Host "Запуск: .\camera-worker\run.ps1"
Write-Host "Если Cursor не видит cmake — перезапустите Cursor."
