# Runs unit tests for all defect-detector-v3 services.
# Integration health checks require a running stack: add -Integration

param(
    [switch]$Integration
)

$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $MyInvocation.MyCommand.Path

function Invoke-Step {
    param(
        [string]$Name,
        [string]$WorkingDirectory,
        [scriptblock]$Action
    )
    Write-Host ""
    Write-Host "== $Name ==" -ForegroundColor Cyan
    Push-Location $WorkingDirectory
    try {
        & $Action
        if ($LASTEXITCODE -ne 0) {
            throw "$Name failed with exit code $LASTEXITCODE"
        }
    }
    finally {
        Pop-Location
    }
}

Invoke-Step "orchestrator-java (JUnit)" (Join-Path $root "orchestrator-java") {
    mvn -q test
}

Invoke-Step "java-geometry-service (JUnit)" (Join-Path $root "java-geometry-service") {
    mvn -q test
}

$backend = Join-Path $root "analisSurface\backend"
$venvPython = Join-Path $backend ".venv\Scripts\python.exe"
$python = if (Test-Path $venvPython) { $venvPython } else { "python" }

Invoke-Step "analisSurface backend (pytest)" $backend {
    & $python -m pip install -q -r requirements-dev.txt
    if ($Integration) {
        & $python -m pytest tests/ -q
    }
    else {
        & $python -m pytest tests/ -q -m "not integration"
    }
}

Write-Host ""
Write-Host "All tests passed." -ForegroundColor Green
