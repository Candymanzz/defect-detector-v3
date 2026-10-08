# Headless stack supervisor for Task Scheduler / Windows autostart.
# Logs to logs\supervisor\supervisor-YYYY-MM-DD.log
# Does NOT call stop-dev on exit — Task Scheduler restarts this script if needed.

param(
    [switch]$NoFrontend,
    [string]$Config = "config\config.yaml"
)

$ErrorActionPreference = "Stop"
$RepoRoot = $PSScriptRoot
Set-Location $RepoRoot

$OrchestratorJar = Join-Path $RepoRoot "orchestrator-java\target\orchestrator-0.1.0-SNAPSHOT.jar"
$ConfigPath = Join-Path $RepoRoot $Config
$LogDir = Join-Path $RepoRoot "logs\supervisor"
$LogFile = Join-Path $LogDir ("supervisor-{0:yyyy-MM-dd}.log" -f (Get-Date))

function Write-Log([string]$Text) {
    $line = "{0:yyyy-MM-dd HH:mm:ss} {1}" -f (Get-Date), $Text
    if (-not (Test-Path $LogDir)) {
        New-Item -ItemType Directory -Path $LogDir -Force | Out-Null
    }
    Add-Content -Path $LogFile -Value $line -Encoding UTF8
}

function Initialize-Env {
    $javaHome = if ($env:JAVA_HOME -and (Test-Path $env:JAVA_HOME)) { $env:JAVA_HOME }
                elseif (Test-Path "C:\dev-tools\jdk-17") { "C:\dev-tools\jdk-17" }
                else { $null }
    if (-not $javaHome) {
        throw "JDK not found. Set JAVA_HOME or install to C:\dev-tools\jdk-17"
    }
    $env:JAVA_HOME = $javaHome
    $javaBin = Join-Path $javaHome "bin"
    if ($env:PATH -notlike "*$javaBin*") {
        $env:PATH = "$javaBin;$env:PATH"
    }
    return (Join-Path $javaBin "java.exe")
}

try {
    Write-Log "headless supervisor starting repo=$RepoRoot config=$ConfigPath"
    $JavaExe = Initialize-Env

    if (-not (Test-Path $ConfigPath)) {
        throw "Config not found: $ConfigPath"
    }
    if (-not (Test-Path $OrchestratorJar)) {
        throw "Orchestrator JAR missing: $OrchestratorJar (run rebuild-and-run.ps1)"
    }

    if ($NoFrontend) {
        $env:IML_FRONTEND_AUTOSTART = "false"
    }
    $env:IML_ORCHESTRATOR_JAR = $OrchestratorJar

    Write-Log "spawn $JavaExe -cp $OrchestratorJar StackSupervisorMain $ConfigPath"

    & $JavaExe `
        -cp $OrchestratorJar `
        com.example.iml.orchestrator.supervisor.StackSupervisorMain `
        $ConfigPath 2>&1 | ForEach-Object {
            Write-Log "$_"
        }

    $exitCode = $LASTEXITCODE
    Write-Log "supervisor exited code=$exitCode"
    exit $exitCode
} catch {
    Write-Log "FATAL $($_.Exception.Message)"
    exit 1
}
