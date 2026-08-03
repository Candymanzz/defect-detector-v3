# Build DefectDetector.exe (no NuGet — uses .NET Framework csc).
# Usage:  .\launcher\build-exe.ps1

$ErrorActionPreference = "Stop"
$LauncherDir = $PSScriptRoot
$RepoRoot = Split-Path $LauncherDir -Parent
$OutExe = Join-Path $RepoRoot "DefectDetector.exe"
$Src = Join-Path $LauncherDir "netfx\Program.cs"
$Csc = Join-Path $env:WINDIR "Microsoft.NET\Framework64\v4.0.30319\csc.exe"

if (-not (Test-Path $Csc)) {
    $Csc = Join-Path $env:WINDIR "Microsoft.NET\Framework\v4.0.30319\csc.exe"
}
if (-not (Test-Path $Csc)) { throw "csc.exe not found (need .NET Framework 4.x)" }
if (-not (Test-Path $Src)) { throw "Source missing: $Src" }

Write-Host "==> Compile DefectDetector.exe" -ForegroundColor Cyan
& $Csc /nologo /optimize+ /target:exe /out:"$OutExe" /reference:System.dll /reference:System.Core.dll /reference:System.Net.Http.dll "$Src"
if ($LASTEXITCODE -ne 0) { throw "csc failed" }
if (-not (Test-Path $OutExe)) { throw "exe missing: $OutExe" }

Write-Host ""
Write-Host "OK: $OutExe" -ForegroundColor Green
Write-Host "Run:  .\DefectDetector.exe"
Write-Host "      .\DefectDetector.exe --no-frontend"
Write-Host "      .\DefectDetector.exe --config config\config.yaml"
