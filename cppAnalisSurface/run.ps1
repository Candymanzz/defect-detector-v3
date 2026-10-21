# Starts cppAnalisSurface (no build). Ctrl+C stops it.
#   .\run.ps1                       default: 127.0.0.1:8000, workers = logical CPU threads, data in .\data
#   .\run.ps1 -Port 8001 -Workers 6 -DataDir D:\data\cas
param(
    [int]$Port = 8000,
    [string]$BindHost = '127.0.0.1',
    [int]$Workers = 0,              # 0 = number of logical CPU threads
    [string]$DataDir = '',
    [string]$Config = 'Release'
)
. (Join-Path $PSScriptRoot 'scripts\common.ps1')

$exe = Get-ExePath $Config
if (-not (Test-Path $exe)) {
    Write-Host "Executable not found: $exe" -ForegroundColor Red
    Write-Host 'Build it first:  .\rebuild-and-run.ps1   (installs missing tools/libraries and builds)' -ForegroundColor Yellow
    exit 1
}

# refuse to start twice on the same port
$busy = $null
try { $busy = Get-NetTCPConnection -State Listen -LocalPort $Port -ErrorAction SilentlyContinue } catch { }
if ($busy) {
    $owner = Get-Process -Id ($busy | Select-Object -First 1).OwningProcess -ErrorAction SilentlyContinue
    Write-Host "Port $Port is already in use by $($owner.ProcessName) (pid $($owner.Id)). Stop it or pass -Port." -ForegroundColor Red
    exit 1
}

if (-not $DataDir) { $DataDir = Join-Path $script:ProjectDir 'data' }
if ($Workers -le 0) { $Workers = [Environment]::ProcessorCount }

Write-Host "cppAnalisSurface: http://${BindHost}:$Port  workers=$Workers  data=$DataDir" -ForegroundColor Green
Write-Host 'Ctrl+C to stop.' -ForegroundColor DarkGray
Set-Location $script:ProjectDir
& $exe --host $BindHost --port $Port --workers $Workers --data-dir $DataDir
exit $LASTEXITCODE
