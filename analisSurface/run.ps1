# Запуск FastAPI analisSurface на :8000
#   .\analisSurface\run.ps1
#   .\analisSurface\run.ps1 -NoReload

param(
    [switch]$NoReload,
    [string]$HostAddress = "127.0.0.1",
    [int]$Port = 8000
)

$ErrorActionPreference = "Stop"
$Backend = Join-Path $PSScriptRoot "backend"
$VenvScripts = Join-Path $Backend ".venv\Scripts"
$VenvPython = Join-Path $VenvScripts "python.exe"

if (-not (Test-Path $VenvPython)) {
    throw "Нет venv: $VenvPython`nСначала: .\analisSurface\setup.ps1"
}

$env:Path = "$VenvScripts;$env:Path"

$uvicornArgs = @("-m", "uvicorn", "app.main:app", "--host", $HostAddress, "--port", "$Port")
if (-not $NoReload) { $uvicornArgs += "--reload" }

Write-Host "analisSurface → http://${HostAddress}:${Port}/health" -ForegroundColor Green
Set-Location $Backend
python @uvicornArgs
