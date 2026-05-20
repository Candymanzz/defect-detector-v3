# Единый запуск dev-стека: Python (uvicorn) → оркестратор (jar) → [опционально] front-end.
# Использование:
#   .\start-dev.ps1
#   .\start-dev.ps1 -WithFrontend
#   .\start-dev.ps1 -Build -Setup
#   .\start-dev.ps1 -OrchestratorOnly
#
# Остановка: Ctrl+C в этом окне или .\stop-dev.ps1

param(
    [switch]$Build,
    [switch]$Setup,
    [switch]$WithFrontend,
    [switch]$OrchestratorOnly,
    [string]$Config = "config\config.yaml"
)

$ErrorActionPreference = "Stop"
$RepoRoot = $PSScriptRoot
Set-Location $RepoRoot

$OrchestratorJar = Join-Path $RepoRoot "orchestrator-java\target\orchestrator-0.1.0-SNAPSHOT.jar"
$PythonBackend = Join-Path $RepoRoot "analisSurface\backend"
$PythonVenv = Join-Path $PythonBackend ".venv"
$PythonExe = Join-Path $PythonVenv "Scripts\python.exe"
$FrontEndDir = Join-Path $RepoRoot "front-end"
$ConfigPath = Join-Path $RepoRoot $Config
$PidFile = Join-Path $RepoRoot ".dev-stack.pids.json"

function Write-Step([string]$Text) { Write-Host "`n==> $Text" -ForegroundColor Cyan }
function Test-Command([string]$Name) {
    if (-not (Get-Command $Name -ErrorAction SilentlyContinue)) {
        throw "Команда не найдена в PATH: $Name"
    }
}

function Wait-HttpOk([string]$Url, [int]$TimeoutSec = 120) {
    $deadline = (Get-Date).AddSeconds($TimeoutSec)
    while ((Get-Date) -lt $deadline) {
        try {
            $r = Invoke-WebRequest -Uri $Url -UseBasicParsing -TimeoutSec 3
            if ($r.StatusCode -ge 200 -and $r.StatusCode -lt 300) { return }
        } catch { }
        Start-Sleep -Seconds 1
    }
    throw "Таймаут ожидания $Url"
}

function Save-Pids([hashtable]$Pids) {
    $Pids | ConvertTo-Json | Set-Content -Path $PidFile -Encoding UTF8
}

Write-Step "Проверка инструментов"
Test-Command "java"
Test-Command "python"
if ($Build) { Test-Command "mvn" }
if ($WithFrontend) { Test-Command "npm" }

if (-not (Test-Path $ConfigPath)) {
    throw "Конфиг не найден: $ConfigPath"
}

if ($Setup) {
    Write-Step "Python venv + pip"
    if (-not (Test-Path $PythonVenv)) {
        python -m venv $PythonVenv
    }
    & $PythonExe -m pip install -q -r (Join-Path $PythonBackend "requirements.txt")

    if ($WithFrontend -and -not (Test-Path (Join-Path $FrontEndDir "node_modules"))) {
        Write-Step "npm install (front-end)"
        Push-Location $FrontEndDir
        npm install
        Pop-Location
    }
}

if ($Build) {
    Write-Step "Сборка orchestrator-java"
    Push-Location (Join-Path $RepoRoot "orchestrator-java")
    mvn -q package -DskipTests
    Pop-Location

    Write-Step "Сборка java-geometry-service"
    Push-Location (Join-Path $RepoRoot "java-geometry-service")
    mvn -q package -DskipTests
    Pop-Location
}

if (-not (Test-Path $OrchestratorJar)) {
    throw "Нет JAR: $OrchestratorJar`nЗапустите: .\start-dev.ps1 -Build"
}

if (-not (Test-Path $PythonExe)) {
    throw "Нет venv: $PythonVenv`nЗапустите: .\start-dev.ps1 -Setup"
}

$stack = @{ python = $null; orchestrator = $null; frontend = $null }

Write-Step "Запуск analisSurface (uvicorn :8000)"
$pythonProc = Start-Process -FilePath $PythonExe `
    -ArgumentList "-m", "uvicorn", "app.main:app", "--host", "127.0.0.1", "--port", "8000" `
    -WorkingDirectory $PythonBackend `
    -PassThru -WindowStyle Hidden
$stack.python = $pythonProc.Id

Write-Step "Ожидание Python http://127.0.0.1:8000/health"
Wait-HttpOk "http://127.0.0.1:8000/health" 180

if ($WithFrontend -and -not $OrchestratorOnly) {
    Write-Step "Запуск front-end (Vite + Electron) в отдельном окне"
    Write-Host "  Только одно окно UI (не браузер + Electron одновременно)" -ForegroundColor Yellow
    $frontProc = Start-Process -FilePath "cmd.exe" `
        -ArgumentList "/c", "npm run dev" `
        -WorkingDirectory $FrontEndDir `
        -PassThru -WindowStyle Normal
    $stack.frontend = $frontProc.Id
}

Save-Pids $stack

Write-Host "`nГотово к запуску оркестратора." -ForegroundColor Green
Write-Host "  Python    : http://127.0.0.1:8000  (PID $($stack.python))"
if ($stack.frontend) {
    Write-Host "  Front-end : http://localhost:5173  (отдельное окно)"
}
Write-Host "  Далее jar поднимет LightServer, camera-worker, geometry, :8099, :8765"
Write-Host "`nОстановка: Ctrl+C в этом окне или .\stop-dev.ps1`n"

Write-Step "Запуск оркестратора (логи ниже, Ctrl+C = стоп всего стека)"
try {
    & java -jar $OrchestratorJar $ConfigPath
} finally {
    Write-Step "Остановка стека"
    & (Join-Path $RepoRoot "stop-dev.ps1") -Quiet
}
