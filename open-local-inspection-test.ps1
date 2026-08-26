$ErrorActionPreference = "Stop"

$RepoRoot = $PSScriptRoot
$BackendDir = Join-Path $RepoRoot "analisSurface\backend"
$PythonExe = Join-Path $BackendDir ".venv\Scripts\python.exe"
$InterfaceUrl = "http://127.0.0.1:8000/local-emulator"
$HealthUrl = "http://127.0.0.1:8000/health"

function Test-HttpPage([string]$Url) {
    try {
        $response = Invoke-WebRequest -Uri $Url -UseBasicParsing -TimeoutSec 2
        return $response.StatusCode -ge 200 -and $response.StatusCode -lt 300
    } catch {
        return $false
    }
}

if (-not (Test-HttpPage $InterfaceUrl)) {
    if (Test-HttpPage $HealthUrl) {
        throw "Python detector is already running, but the interface is unavailable. Restart DefectDetector to load the current branch."
    }
    if (-not (Test-Path -LiteralPath $PythonExe)) {
        throw "Python environment not found: $PythonExe"
    }

    $backendProcess = Start-Process -FilePath $PythonExe `
        -ArgumentList "-m", "uvicorn", "app.main:app", "--host", "127.0.0.1", "--port", "8000" `
        -WorkingDirectory $BackendDir `
        -PassThru `
        -WindowStyle Hidden

    $deadline = (Get-Date).AddSeconds(30)
    while ((Get-Date) -lt $deadline) {
        if ($backendProcess.HasExited) {
            throw "Python detector stopped during startup with exit code $($backendProcess.ExitCode)."
        }
        if (Test-HttpPage $InterfaceUrl) {
            break
        }
        Start-Sleep -Milliseconds 400
    }
    if (-not (Test-HttpPage $InterfaceUrl)) {
        if (-not $backendProcess.HasExited) {
            Stop-Process -Id $backendProcess.Id -Force -ErrorAction SilentlyContinue
        }
        throw "Timed out waiting for $InterfaceUrl"
    }
}

Start-Process $InterfaceUrl
