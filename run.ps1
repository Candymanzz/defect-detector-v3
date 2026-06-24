# Simple launch: cleanup stale processes, then orchestrator + front-end.
# Usage:
#   .\run.ps1
#   .\run.ps1 -NoFrontend
# Stop: Ctrl+C in this window (also runs cleanup).

param(
    [switch]$NoFrontend,
    [string]$Config = "config\config.yaml"
)

$ErrorActionPreference = "Stop"
$RepoRoot = $PSScriptRoot
Set-Location $RepoRoot

$OrchestratorJar = Join-Path $RepoRoot "orchestrator-java\target\orchestrator-0.1.0-SNAPSHOT.jar"
$GeometryJar = Join-Path $RepoRoot "java-geometry-service\target\java-geometry-service-0.1.0-SNAPSHOT.jar"
$PythonExe = Join-Path $RepoRoot "analisSurface\backend\.venv\Scripts\python.exe"
$LightServerDll = Join-Path $RepoRoot "LightServer.v3\bin\Release\net10.0\LightServer.dll"
$CameraWorker = Join-Path $RepoRoot "camera-worker\build\Debug\camera_worker.exe"
$CameraWorkerAlt = Join-Path $RepoRoot "camera-worker\build\Release\camera_worker.exe"
$FrontEndDir = Join-Path $RepoRoot "front-end"
$ConfigPath = Join-Path $RepoRoot $Config
$PidFile = Join-Path $RepoRoot ".dev-stack.pids.json"
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

function Save-Pids([hashtable]$Pids) {
    $Pids | ConvertTo-Json | Set-Content -Path $PidFile -Encoding UTF8
}

Write-Step "Cleanup stale processes and ports"
& (Join-Path $RepoRoot "stop-dev.ps1") -Quiet

Write-Step "Environment"
Initialize-Env
Test-Tool "java"

if (-not (Test-Path $ConfigPath)) { throw "Config not found: $ConfigPath" }
if (-not (Test-Path $OrchestratorJar)) {
    throw "Orchestrator JAR missing. Run: .\rebuild-and-run.ps1"
}
if (-not (Test-Path $GeometryJar)) {
    throw "Geometry JAR missing. Run: .\rebuild-and-run.ps1"
}
if (-not (Test-Path $PythonExe)) {
    throw "Python venv missing. Run: .\rebuild-and-run.ps1"
}
if (-not (Test-Path $LightServerDll)) {
    throw "LightServer.dll missing. Run: .\rebuild-and-run.ps1"
}
if (-not ((Test-Path $CameraWorker) -or (Test-Path $CameraWorkerAlt))) {
    throw "camera_worker.exe missing. Run: .\rebuild-and-run.ps1"
}

$pids = @{ orchestrator = $null; frontend = $null; python = $null }

if (-not $NoFrontend) {
    if (-not (Test-Path $NpmCmd)) { throw "npm.cmd not found: $NpmCmd" }
    if (-not (Test-Path (Join-Path $FrontEndDir "node_modules"))) {
        throw "front-end/node_modules missing. Run: .\rebuild-and-run.ps1"
    }

    Write-Step "Start front-end (separate window)"
    $frontProc = Start-Process -FilePath "cmd.exe" `
        -ArgumentList "/k", "cd /d `"$FrontEndDir`" && `"$NpmCmd`" run dev" `
        -PassThru -WindowStyle Normal
    $pids.frontend = $frontProc.Id
    Write-Host "  Front-end window PID $($pids.frontend)" -ForegroundColor Gray
}

Save-Pids $pids

Write-Host ""
Write-Host "Starting orchestrator (logs below). Ctrl+C = stop all." -ForegroundColor Green
Write-Host "  API : http://127.0.0.1:8099" -ForegroundColor Gray
Write-Host "  WS  : ws://127.0.0.1:8765" -ForegroundColor Gray
if (-not $NoFrontend) {
    Write-Host "  UI  : http://localhost:5173" -ForegroundColor Gray
}
Write-Host ""

try {
    Write-Step "Start orchestrator"
    & java -jar $OrchestratorJar $ConfigPath
} finally {
    Write-Step "Cleanup"
    & (Join-Path $RepoRoot "stop-dev.ps1") -Quiet
}
