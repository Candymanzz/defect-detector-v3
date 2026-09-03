# Register Defect Detector in Windows Task Scheduler (autostart).
# Requires Administrator.
#
# Default: DefectDetector.exe (GUI splash + crash-recovery supervisor after boot).
# Fallback: headless StackSupervisorMain via run-supervised-headless.ps1 (-Headless).
#
# After 3 failed recoveries supervisor may reboot Windows (shutdown /r).
# Disable reboot: IML_SUPERVISOR_REBOOT_ENABLED=false
#
# Usage (PowerShell as Admin):
#   .\install-windows-autostart.ps1
#   .\install-windows-autostart.ps1 -AtLogon
#   .\install-windows-autostart.ps1 -Headless -BootDelaySec 60
#   .\install-windows-autostart.ps1 -NoFrontend
#
# Remove: .\uninstall-windows-autostart.ps1

param(
    [switch]$NoFrontend,
    [switch]$AtLogon,
    [switch]$Headless,
    [string]$Config = "config\config.yaml",
    [string]$TaskName = "IML-DefectDetector",
    [string]$LegacyTaskName = "IML-StackSupervisor",
    [int]$BootDelaySec = 30
)

$ErrorActionPreference = "Stop"
$RepoRoot = $PSScriptRoot
$ExePath = Join-Path $RepoRoot "DefectDetector.exe"
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

function Remove-TaskIfExists([string]$Name) {
    $existing = Get-ScheduledTask -TaskName $Name -ErrorAction SilentlyContinue
    if ($existing) {
        Write-Host "Removing existing task '$Name'." -ForegroundColor Yellow
        Stop-ScheduledTask -TaskName $Name -ErrorAction SilentlyContinue
        Unregister-ScheduledTask -TaskName $Name -Confirm:$false
    }
}

if (-not (Test-Administrator)) {
    throw "Run PowerShell as Administrator (right-click -> Run as administrator)."
}

$ConfigPath = Join-Path $RepoRoot $Config
if (-not (Test-Path $ConfigPath)) {
    throw "Config not found: $ConfigPath"
}

$useExe = -not $Headless
if ($useExe -and -not (Test-Path $ExePath)) {
    Write-Host "DefectDetector.exe missing - building..." -ForegroundColor Yellow
    & powershell -NoProfile -ExecutionPolicy Bypass -File (Join-Path $RepoRoot "launcher\build-exe.ps1")
}
if ($useExe -and -not (Test-Path $ExePath)) {
    throw "DefectDetector.exe not found. Build: .\launcher\build-exe.ps1  (or pass -Headless)"
}

$OrchestratorJar = Join-Path $RepoRoot "orchestrator-java\target\orchestrator-0.1.0-SNAPSHOT.jar"
if (-not (Test-Path $OrchestratorJar)) {
    throw "Orchestrator JAR missing. Build first: .\rebuild-and-run.ps1"
}

if ($Headless -and -not (Test-Path $HeadlessScript)) {
    throw "Missing script: $HeadlessScript"
}

# Prefer interactive logon for GUI exe (SYSTEM session has no desktop for splash).
if ($useExe -and -not $AtLogon -and -not $PSBoundParameters.ContainsKey("AtLogon")) {
    $AtLogon = $true
}

Write-Step "Prepare scheduled task '$TaskName'"

if ($useExe) {
    $exeArgs = @("--config", $Config)
    if ($NoFrontend) {
        $exeArgs += "--no-frontend"
    }
    $Argument = ($exeArgs -join " ")
    $Action = New-ScheduledTaskAction `
        -Execute $ExePath `
        -Argument $Argument `
        -WorkingDirectory $RepoRoot
    $modeDesc = "DefectDetector.exe (GUI + supervisor)"
} else {
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
    $modeDesc = "headless StackSupervisorMain"
}

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
    $triggerDesc = "at Windows startup (SYSTEM delay ${BootDelaySec}s)"
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
Defect Detector autostart ($modeDesc).
Repo: $RepoRoot
Config: $Config
Exe starts StackSupervisorMain after critical services are up.
Logs: $RepoRoot\logs\supervisor\
"@

Remove-TaskIfExists $TaskName
Remove-TaskIfExists $LegacyTaskName

Register-ScheduledTask `
    -TaskName $TaskName `
    -Action $Action `
    -Trigger $Trigger `
    -Settings $Settings `
    -Principal $Principal `
    -Description $Description | Out-Null

# One launcher only: Task Scheduler. Do not also put a Startup .lnk (would double-start).
$StartupDir = [Environment]::GetFolderPath("Startup")
$ShortcutPath = Join-Path $StartupDir "DefectDetector.lnk"
if (Test-Path $ShortcutPath) {
    Remove-Item $ShortcutPath -Force
    Write-Host "Removed leftover Startup shortcut (task covers autostart)." -ForegroundColor Yellow
}

Write-Step "Registered"
Write-Host "  Task name : $TaskName" -ForegroundColor Green
Write-Host "  Mode      : $modeDesc" -ForegroundColor Green
Write-Host "  Trigger   : $triggerDesc" -ForegroundColor Gray
Write-Host "  Target    : $(if ($useExe) { $ExePath } else { $HeadlessScript })" -ForegroundColor Gray
Write-Host "  Logs      : $RepoRoot\logs\supervisor\" -ForegroundColor Gray
Write-Host ""
Write-Host "Do NOT start the task now if rebuild-and-run / stack is already running." -ForegroundColor Yellow
Write-Host "After reboot / next logon the exe will start then the supervisor attaches." -ForegroundColor Gray
Write-Host ""
Write-Host "Start now (optional - stop conflicting stack first):" -ForegroundColor Yellow
Write-Host "  .\stop-dev.ps1; Start-ScheduledTask -TaskName '$TaskName'" -ForegroundColor White
Write-Host ""
Write-Host "Check status:" -ForegroundColor Yellow
Write-Host "  Get-ScheduledTask -TaskName '$TaskName' | Get-ScheduledTaskInfo" -ForegroundColor White
Write-Host ""
Write-Host "Remove autostart:" -ForegroundColor Yellow
Write-Host "  .\uninstall-windows-autostart.ps1" -ForegroundColor White
