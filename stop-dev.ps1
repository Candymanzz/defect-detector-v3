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

function Send-LightBankOff {
    try {
        $body = '{"state":"off"}'
        Invoke-RestMethod -Method Post -Uri "http://127.0.0.1:5080/api/camera-flash/bank" `
            -ContentType "application/json; charset=utf-8" -Body $body -TimeoutSec 2 | Out-Null
        if (-not $Quiet) {
            Write-Host "LightServer bank Off отправлен." -ForegroundColor DarkYellow
        }
    } catch {
        # LightServer уже не слушает — ок
    }
}

# Сначала гасим MV-LE: force-kill не вызывает Dispose/ApplyAllOff.
Send-LightBankOff

if (Test-Path $PidFile) {
    $pids = Get-Content $PidFile -Raw | ConvertFrom-Json
    Stop-PidSafe $pids.python
    Stop-PidSafe $pids.orchestrator
    Stop-PidSafe $pids.frontend
    Remove-Item $PidFile -Force
}

# Порты: analisSurface pool 8000..8009 (python_parallelism), UI HTTP, WS, Vite, LightServer, fan-out stub
foreach ($port in 8000..8009 + @(8099, 8765, 5173, 5079, 5080, 8088)) {
    Get-NetTCPConnection -LocalPort $port -State Listen -ErrorAction SilentlyContinue |
        ForEach-Object { Stop-PidSafe $_.OwningProcess }
}

if (-not $Quiet) {
    Write-Host "Dev-стек остановлен." -ForegroundColor Green
}
