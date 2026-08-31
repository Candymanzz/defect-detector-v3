# Register stack supervisor in Windows Task Scheduler.
# Requires Administrator.
#
# NOTE: Рекомендуемый способ — DefectDetector.exe (supervisor стартует сам после boot).
# После 3 неудачных recovery supervisor может перезагрузить Windows (shutdown /r).
# Требуются права администратора для reboot. Отключить: IML_SUPERVISOR_REBOOT_ENABLED=false
# Этот скрипт — только для машин БЕЗ exe, где нужен автозапуск при включении Windows.
#
# Usage (PowerShell as Admin):
#   .\install-windows-autostart.ps1
#   .\install-windows-autostart.ps1 -AtLogon
#   .\install-windows-autostart.ps1 -NoFrontend -BootDelaySec 60
#
# Remove: .\uninstall-windows-autostart.ps1

param(
    [switch]$NoFrontend,
    [switch]$AtLogon,
    [string]$Config = "config\config.yaml",
    [string]$TaskName = "IML-StackSupervisor",
    [int]$BootDelaySec = 30
)

$ErrorActionPreference = "Stop"
$RepoRoot = $PSScriptRoot
$HeadlessScript = Join-Path $RepoRoot "run-supervised-headless.ps1"

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
    throw "Run PowerShell as Administrator (right-click -> Run as administrator)."
}
if (-not (Test-Path $HeadlessScript)) {
    throw "Missing script: $HeadlessScript"
}

$OrchestratorJar = Join-Path $RepoRoot "orchestrator-java\target\orchestrator-0.1.0-SNAPSHOT.jar"
if (-not (Test-Path $OrchestratorJar)) {
    throw "Orchestrator JAR missing. Build first: .\rebuild-and-run.ps1"
}
$ConfigPath = Join-Path $RepoRoot $Config
if (-not (Test-Path $ConfigPath)) {
    throw "Config not found: $ConfigPath"
}

Write-Step "Prepare scheduled task '$TaskName'"

$argList = @(
    "-NoProfile",
    "-ExecutionPolicy", "Bypass",
    "-WindowStyle", "Hidden",
    "-File", "`"$HeadlessScript`"",
    "-Config", "`"$Config`""
)
if ($NoFrontend) {
    $argList += "-NoFrontend"
}
$Argument = ($argList -join " ")

$Action = New-ScheduledTaskAction `
    -Execute "powershell.exe" `
    -Argument $Argument `
    -WorkingDirectory $RepoRoot

if ($AtLogon) {
    $Trigger = New-ScheduledTaskTrigger -AtLogon -User $env:USERNAME
    $Principal = New-ScheduledTaskPrincipal `
        -UserId "$env:USERDOMAIN\$env:USERNAME" `
        -LogonType Interactive `
        -RunLevel Highest
    $triggerDesc = "at logon for $env:USERNAME"
} else {
    $Trigger = New-ScheduledTaskTrigger -AtStartup
    if ($BootDelaySec -gt 0) {
        $Trigger.Delay = "PT${BootDelaySec}S"
    }
    $Principal = New-ScheduledTaskPrincipal `
        -UserId "SYSTEM" `
        -LogonType ServiceAccount `
        -RunLevel Highest
    $triggerDesc = "at Windows startup (SYSTEM, delay ${BootDelaySec}s)"
}

$Settings = New-ScheduledTaskSettingsSet `
    -AllowStartIfOnBatteries `
    -DontStopIfGoingOnBatteries `
    -StartWhenAvailable `
    -MultipleInstances IgnoreNew `
    -RestartCount 999 `
    -RestartInterval (New-TimeSpan -Minutes 1) `
    -ExecutionTimeLimit ([TimeSpan]::Zero)

$Description = @"
Defect Detector stack supervisor (auto-restart orchestrator on crash).
Repo: $RepoRoot
Config: $Config
Logs: $RepoRoot\logs\supervisor\
"@

$existing = Get-ScheduledTask -TaskName $TaskName -ErrorAction SilentlyContinue
if ($existing) {
    Write-Host "Task '$TaskName' already exists — updating." -ForegroundColor Yellow
    Unregister-ScheduledTask -TaskName $TaskName -Confirm:$false
}

Register-ScheduledTask `
    -TaskName $TaskName `
    -Action $Action `
    -Trigger $Trigger `
    -Settings $Settings `
    -Principal $Principal `
    -Description $Description | Out-Null

Write-Step "Registered"
Write-Host "  Task name : $TaskName" -ForegroundColor Green
Write-Host "  Trigger   : $triggerDesc" -ForegroundColor Gray
Write-Host "  Script    : $HeadlessScript" -ForegroundColor Gray
Write-Host "  Logs      : $RepoRoot\logs\supervisor\" -ForegroundColor Gray
Write-Host ""
Write-Host "Start now (optional):" -ForegroundColor Yellow
Write-Host "  Start-ScheduledTask -TaskName '$TaskName'" -ForegroundColor White
Write-Host ""
Write-Host "Check status:" -ForegroundColor Yellow
Write-Host "  Get-ScheduledTask -TaskName '$TaskName' | Get-ScheduledTaskInfo" -ForegroundColor White
Write-Host ""
Write-Host "Remove autostart:" -ForegroundColor Yellow
Write-Host "  .\uninstall-windows-autostart.ps1" -ForegroundColor White
