# Supervised launch: external JVM monitors orchestrator, restarts on crash/OOM/kill.
# Usage:
#   .\run-supervised.ps1
#   .\run-supervised.ps1 -NoFrontend
# Stop: Ctrl+C (supervisor stops orchestrator and cleans ports).

param(
    [switch]$NoFrontend,
    [string]$Config = "config\config.yaml"
)

$ErrorActionPreference = "Stop"
$RepoRoot = $PSScriptRoot
Set-Location $RepoRoot

$OrchestratorJar = Join-Path $RepoRoot "orchestrator-java\target\orchestrator-0.1.0-SNAPSHOT.jar"
$ConfigPath = Join-Path $RepoRoot $Config

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
    $javaBin = Join-Path $javaHome "bin"
    if ($env:PATH -notlike "*$javaBin*") {
        $env:PATH = "$javaBin;$env:PATH"
    }
}

Write-Step "Cleanup stale processes and ports"
& (Join-Path $RepoRoot "stop-dev.ps1") -Quiet

Write-Step "Environment"
Initialize-Env

if (-not (Test-Path $ConfigPath)) { throw "Config not found: $ConfigPath" }
if (-not (Test-Path $OrchestratorJar)) {
    throw "Orchestrator JAR missing. Run: .\rebuild-and-run.ps1"
}

if ($NoFrontend) {
    $env:IML_FRONTEND_AUTOSTART = "false"
}

$env:IML_ORCHESTRATOR_JAR = $OrchestratorJar

Write-Host ""
Write-Host "Starting stack supervisor (auto-restart on crash). Ctrl+C = stop all." -ForegroundColor Green
Write-Host "  Orchestrator health: http://127.0.0.1:8099/health" -ForegroundColor Gray
Write-Host "  Windows autostart: run install-windows-autostart.ps1 as Administrator" -ForegroundColor Gray
Write-Host ""

try {
    Write-Step "Start stack supervisor"
    & java -cp $OrchestratorJar com.example.iml.orchestrator.supervisor.StackSupervisorMain $ConfigPath
} finally {
    Write-Step "Cleanup"
    & (Join-Path $RepoRoot "stop-dev.ps1") -Quiet
}
