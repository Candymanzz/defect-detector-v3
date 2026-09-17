# Запуск front-end (Vite + Electron)
#   .\front-end\run.ps1
#   .\front-end\run.ps1 -RendererOnly

param(
    [switch]$RendererOnly
)

$ErrorActionPreference = "Stop"
$FrontEndDir = $PSScriptRoot
$NodeDir = "C:\Program Files\nodejs"
$BinDir = Join-Path $FrontEndDir "node_modules\.bin"

if (-not (Test-Path (Join-Path $FrontEndDir "node_modules"))) {
    throw "Нет node_modules. Сначала: .\front-end\setup.ps1"
}

$env:Path = "$BinDir;$NodeDir;$env:Path"
Set-Location $FrontEndDir

if ($RendererOnly) {
    Write-Host "front-end renderer → http://localhost:5173" -ForegroundColor Green
    npm run dev:renderer
} else {
    Write-Host "front-end → Vite + Electron (http://localhost:5173)" -ForegroundColor Green
    npm run dev
}
