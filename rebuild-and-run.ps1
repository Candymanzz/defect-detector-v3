# Full rebuild + launch. Cleanup first, then build all components, then run.
# Usage:
#   .\rebuild-and-run.ps1
#   .\rebuild-and-run.ps1 -NoFrontend
#   .\rebuild-and-run.ps1 -SkipCameraWorker
#   .\rebuild-and-run.ps1 -SkipLightServer

param(
    [switch]$NoFrontend,
    [switch]$SkipCameraWorker,
    [switch]$SkipLightServer,
    [string]$Config = "config\config.yaml"
)

$ErrorActionPreference = "Stop"
$RepoRoot = $PSScriptRoot
Set-Location $RepoRoot

$OrchestratorJar = Join-Path $RepoRoot "orchestrator-java\target\orchestrator-0.1.0-SNAPSHOT.jar"
$GeometryJar = Join-Path $RepoRoot "java-geometry-service\target\java-geometry-service-0.1.0-SNAPSHOT.jar"
$PythonBackend = Join-Path $RepoRoot "analisSurface\backend"
$PythonVenv = Join-Path $PythonBackend ".venv"
$PythonExe = Join-Path $PythonVenv "Scripts\python.exe"
$LightServerProj = Join-Path $RepoRoot "LightServer.v3\LightServer.csproj"
$LightServerDll = Join-Path $RepoRoot "LightServer.v3\bin\Release\net10.0\LightServer.dll"
$GpioBridgeProj = Join-Path $RepoRoot "tools\GpioUdpBridge\GpioUdpBridge.csproj"
$GpioBridgeDll = Join-Path $RepoRoot "tools\GpioUdpBridge\bin\Release\net10.0\GpioUdpBridge.dll"
$CameraWorkerDir = Join-Path $RepoRoot "camera-worker"
$FrontEndDir = Join-Path $RepoRoot "front-end"
$NpmCmd = "C:\Program Files\nodejs\npm.cmd"

function Write-Step([string]$Text) {
    Write-Host ""
    Write-Host "==> $Text" -ForegroundColor Cyan
}

function Initialize-Env {
    $javaHome = if ($env:JAVA_HOME -and (Test-Path $env:JAVA_HOME)) { $env:JAVA_HOME }
                elseif (Test-Path "C:\dev-tools\jdk-17") { "C:\dev-tools\jdk-17" }
                else { $null }
    if (-not $javaHome) { throw "JDK not found. Set JAVA_HOME or install to C:\dev-tools\jdk-17" }
    $env:JAVA_HOME = $javaHome

    if (-not $env:MVS_ROOT) {
        foreach ($mvsRoot in @("C:\Program Files (x86)\MVS", "E:\MVS")) {
            if (Test-Path (Join-Path $mvsRoot "Development")) {
                $env:MVS_ROOT = $mvsRoot
                break
            }
        }
    }

    $extra = @(
        "$javaHome\bin",
        "C:\dev-tools\maven\bin",
        "C:\Program Files\Python312",
        "C:\Program Files\Python312\Scripts",
        "C:\Program Files\dotnet",
        "C:\Program Files\nodejs"
    )
    foreach ($dir in $extra) {
        if ((Test-Path $dir) -and ($env:PATH -notlike "*$dir*")) {
            $env:PATH = "$dir;$env:PATH"
        }
    }
}

function Test-Tool([string]$Name) {
    if (-not (Get-Command $Name -ErrorAction SilentlyContinue)) {
        throw "Command not found in PATH: $Name"
    }
}

function Invoke-BuildStep([string]$Title, [scriptblock]$Action) {
    Write-Step $Title
    & $Action
    if ($LASTEXITCODE -and $LASTEXITCODE -ne 0) {
        throw "$Title failed (exit $LASTEXITCODE)"
    }
}

Write-Step "Cleanup stale processes and ports"
& (Join-Path $RepoRoot "stop-dev.ps1") -Quiet

Write-Step "Check build tools"
Initialize-Env
Test-Tool "java"
Test-Tool "mvn"
Test-Tool "python"
Test-Tool "dotnet"

Invoke-BuildStep "Build orchestrator-java" {
    Push-Location (Join-Path $RepoRoot "orchestrator-java")
    mvn -q package -DskipTests
    Pop-Location
}
if (-not (Test-Path $OrchestratorJar)) { throw "Build failed: $OrchestratorJar" }

Invoke-BuildStep "Build java-geometry-service" {
    Push-Location (Join-Path $RepoRoot "java-geometry-service")
    mvn -q package -DskipTests
    Pop-Location
}
if (-not (Test-Path $GeometryJar)) { throw "Build failed: $GeometryJar" }

Invoke-BuildStep "Python venv + pip" {
    if (-not (Test-Path $PythonVenv)) {
        python -m venv $PythonVenv
    }
    & $PythonExe -m pip install -q -r (Join-Path $PythonBackend "requirements.txt")
}

if (-not $SkipLightServer) {
    Write-Step "Build LightServer.v3 (Release)"
    dotnet build $LightServerProj -c Release
    if ($LASTEXITCODE -ne 0) {
        if (Test-Path $LightServerDll) {
            Write-Host "WARN: LightServer build failed, using existing DLL." -ForegroundColor Yellow
        } else {
            throw "LightServer build failed and no Release DLL found. Install Hik MVS SDK or use -SkipLightServer"
        }
    }
} elseif (-not (Test-Path $LightServerDll)) {
    throw "LightServer.dll missing and -SkipLightServer was set"
}

Invoke-BuildStep "Build GpioUdpBridge (Release)" {
    dotnet build $GpioBridgeProj -c Release
}
if (-not (Test-Path $GpioBridgeDll)) { throw "Build failed: $GpioBridgeDll" }

if (-not $SkipCameraWorker) {
  if (Get-Command cmake -ErrorAction SilentlyContinue) {
    Invoke-BuildStep "Build camera-worker" {
        Push-Location $CameraWorkerDir
        $mvsDevRoot = $null
        if ($env:MVS_ROOT) {
            if (Test-Path (Join-Path $env:MVS_ROOT "Includes\MvCameraControl.h")) {
                $mvsDevRoot = $env:MVS_ROOT
            } elseif (Test-Path (Join-Path $env:MVS_ROOT "Development\Includes\MvCameraControl.h")) {
                $mvsDevRoot = Join-Path $env:MVS_ROOT "Development"
            }
        } else {
            foreach ($candidate in @("E:\MVS\Development", "C:\Program Files (x86)\MVS\Development")) {
                if (Test-Path (Join-Path $candidate "Includes\MvCameraControl.h")) {
                    $mvsDevRoot = $candidate
                    break
                }
            }
        }
        $cmakeArgs = @("-B", "build", "-DCMAKE_BUILD_TYPE=Release")
        if ($mvsDevRoot) {
            $cmakeArgs += "-DMVS_ROOT=$($mvsDevRoot -replace '\\','/')"
        }
        cmake @cmakeArgs
        cmake --build build --config Release
        Pop-Location
    }
  } else {
    $hasWorker = (Test-Path (Join-Path $CameraWorkerDir "build\Release\camera_worker.exe")) `
              -or (Test-Path (Join-Path $CameraWorkerDir "build\Debug\camera_worker.exe"))
    if ($hasWorker) {
        Write-Host "WARN: cmake not in PATH, using existing camera_worker.exe" -ForegroundColor Yellow
    } else {
        throw "cmake not found and camera_worker.exe missing. Install CMake or use -SkipCameraWorker"
    }
  }
}

Invoke-BuildStep "npm install (front-end)" {
    if (-not (Test-Path $NpmCmd)) { throw "npm.cmd not found: $NpmCmd" }
    Push-Location $FrontEndDir
    & $NpmCmd install
    Pop-Location
}

Write-Step "Build complete, starting stack"
$runArgs = @()
if ($NoFrontend) { $runArgs += "-NoFrontend" }
if ($Config -ne "config\config.yaml") { $runArgs += "-Config"; $runArgs += $Config }

& (Join-Path $RepoRoot "run.ps1") @runArgs
