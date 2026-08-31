# Remove stack supervisor from Windows Task Scheduler and stop running stack.
#
# Usage (PowerShell as Admin):
#   .\uninstall-windows-autostart.ps1
#   .\uninstall-windows-autostart.ps1 -TaskName IML-StackSupervisor

param(
    [string]$TaskName = "IML-StackSupervisor"
)

$ErrorActionPreference = "Stop"
$RepoRoot = $PSScriptRoot

function Test-Administrator {
    $identity = [Security.Principal.WindowsIdentity]::GetCurrent()
    $principal = New-Object Security.Principal.WindowsPrincipal($identity)
    return $principal.IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)
}

function Write-Step([string]$Text) {
    Write-Host ""
    Write-Host "==> $Text" -ForegroundColor Cyan
}

if (-not (Test-Administrator)) {
    throw "Run PowerShell as Administrator."
}

Write-Step "Stop scheduled task '$TaskName'"
$task = Get-ScheduledTask -TaskName $TaskName -ErrorAction SilentlyContinue
if ($task) {
    Stop-ScheduledTask -TaskName $TaskName -ErrorAction SilentlyContinue
    Unregister-ScheduledTask -TaskName $TaskName -Confirm:$false
    Write-Host "Task removed." -ForegroundColor Green
} else {
    Write-Host "Task '$TaskName' not found (already removed)." -ForegroundColor Yellow
}

Write-Step "Stop dev stack processes"
& (Join-Path $RepoRoot "stop-dev.ps1") -Quiet

Write-Host ""
Write-Host "Autostart disabled." -ForegroundColor Green
