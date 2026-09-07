# Remove Defect Detector autostart (Task Scheduler + Startup shortcut) and stop stack.
#
# Usage (PowerShell as Admin):
#   .\uninstall-windows-autostart.ps1
#   .\uninstall-windows-autostart.ps1 -TaskName IML-DefectDetector

param(
    [string]$TaskName = "IML-DefectDetector",
    [string]$LegacyTaskName = "IML-StackSupervisor"
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

function Remove-TaskIfExists([string]$Name) {
    $task = Get-ScheduledTask -TaskName $Name -ErrorAction SilentlyContinue
    if ($task) {
        Stop-ScheduledTask -TaskName $Name -ErrorAction SilentlyContinue
        Unregister-ScheduledTask -TaskName $Name -Confirm:$false
        Write-Host "Task '$Name' removed." -ForegroundColor Green
    } else {
        Write-Host "Task '$Name' not found." -ForegroundColor Yellow
    }
}

if (-not (Test-Administrator)) {
    throw "Run PowerShell as Administrator."
}

Write-Step "Remove scheduled tasks"
Remove-TaskIfExists $TaskName
Remove-TaskIfExists $LegacyTaskName

Write-Step "Remove Startup shortcut"
$ShortcutPath = Join-Path ([Environment]::GetFolderPath("Startup")) "DefectDetector.lnk"
if (Test-Path $ShortcutPath) {
    Remove-Item $ShortcutPath -Force
    Write-Host "Removed $ShortcutPath" -ForegroundColor Green
} else {
    Write-Host "Startup shortcut not found." -ForegroundColor Yellow
}

Write-Step "Stop dev stack processes"
& (Join-Path $RepoRoot "stop-dev.ps1") -Quiet

Write-Host ""
Write-Host "Autostart disabled." -ForegroundColor Green
