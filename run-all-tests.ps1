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

$frontend = Join-Path $root "front-end"
Invoke-Step "front-end (Vitest)" $frontend {
    if (-not (Test-Path (Join-Path $frontend "node_modules"))) {
        npm install
    }
    npm test
}

$ioInputTests = Join-Path $root "IoInputMonitor\IoInputMonitor.Tests"
if (Test-Path $ioInputTests) {
    Invoke-Step "IoInputMonitor (xUnit)" $ioInputTests {
        dotnet test -c Release --no-restore 2>$null
        if ($LASTEXITCODE -ne 0) {
            dotnet test -c Release
        }
    }
}

$lightTests = Join-Path $root "LightServer.v3\LightServer.Tests"
if (Test-Path $lightTests) {
    Invoke-Step "LightServer.v3 (xUnit)" $lightTests {
        dotnet test -c Release --no-restore 2>$null
        if ($LASTEXITCODE -ne 0) {
            dotnet test -c Release
        }
    }
}

$cameraWorker = Join-Path $root "camera-worker"
if (Test-Path (Join-Path $cameraWorker "CMakeLists.txt")) {
    Invoke-Step "camera-worker (CTest)" $cameraWorker {
        $cache = Join-Path "build-test" "CMakeCache.txt"
        if (Test-Path $cache) {
            $cacheText = Get-Content $cache -Raw
            if ($cacheText -match '/home/' -or $cacheText -notmatch [regex]::Escape((Get-Location).Path)) {
                Remove-Item -Recurse -Force "build-test"
            }
        }
        if (-not (Test-Path "build-test")) {
            cmake -S . -B build-test | Out-Host
        }
        cmake --build build-test --config Release --target cw_config_tests | Out-Host
        ctest --test-dir build-test -C Release --output-on-failure | Out-Host
    }
}

Write-Host ""
Write-Host "All tests passed." -ForegroundColor Green
