# Остановка процессов, запущенных start-dev.ps1, и типичных портов стека.

param([switch]$Quiet)

$ErrorActionPreference = "SilentlyContinue"
$RepoRoot = $PSScriptRoot
$PidFile = Join-Path $RepoRoot ".dev-stack.pids.json"

function Stop-PidSafe([int]$ProcessId) {
    if ($ProcessId -gt 0) {
        Stop-Process -Id $ProcessId -Force -ErrorAction SilentlyContinue
    }
}

if (Test-Path $PidFile) {
    $pids = Get-Content $PidFile -Raw | ConvertFrom-Json
    Stop-PidSafe $pids.python
    Stop-PidSafe $pids.orchestrator
    Stop-PidSafe $pids.frontend
    Remove-Item $PidFile -Force
}

# Порты: Python, UI HTTP, WS, Vite, LightServer x2, fan-out stub
foreach ($port in @(8000, 8099, 8765, 5173, 5079, 5080, 8088)) {
    Get-NetTCPConnection -LocalPort $port -State Listen -ErrorAction SilentlyContinue |
        ForEach-Object { Stop-PidSafe $_.OwningProcess }
}

if (-not $Quiet) {
    Write-Host "Dev-стек остановлен." -ForegroundColor Green
}
