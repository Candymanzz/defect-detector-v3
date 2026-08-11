# Build DefectDetector.exe — WinForms splash launcher (no NuGet, .NET Framework csc).
# Usage:  .\launcher\build-exe.ps1

$ErrorActionPreference = "Stop"
$LauncherDir = $PSScriptRoot
$RepoRoot = Split-Path $LauncherDir -Parent
$OutExe = Join-Path $RepoRoot "DefectDetector.exe"
$SrcDir = Join-Path $LauncherDir "netfx"
$Sources = @(Get-ChildItem -Path $SrcDir -Filter "*.cs" | ForEach-Object { $_.FullName })
$Csc = Join-Path $env:WINDIR "Microsoft.NET\Framework64\v4.0.30319\csc.exe"

if (-not (Test-Path $Csc)) {
    $Csc = Join-Path $env:WINDIR "Microsoft.NET\Framework\v4.0.30319\csc.exe"
}
if (-not (Test-Path $Csc)) { throw "csc.exe not found (need .NET Framework 4.x)" }
if ($Sources.Count -eq 0) { throw "No .cs sources in $SrcDir" }

Write-Host "==> Compile DefectDetector.exe (winexe splash)" -ForegroundColor Cyan
$names = ($Sources | ForEach-Object { Split-Path $_ -Leaf }) -join ", "
Write-Host "  sources: $names"

$cscArgs = @(
    "/nologo",
    "/optimize+",
    "/target:winexe",
    "/out:$OutExe",
    "/reference:System.dll",
    "/reference:System.Core.dll",
    "/reference:System.Drawing.dll",
    "/reference:System.Net.Http.dll",
    "/reference:System.Windows.Forms.dll"
) + $Sources

& $Csc @cscArgs
if ($LASTEXITCODE -ne 0) { throw "csc failed" }
if (-not (Test-Path $OutExe)) { throw "exe missing: $OutExe" }

Write-Host ""
Write-Host "OK: $OutExe" -ForegroundColor Green
Write-Host "Run:  .\DefectDetector.exe"
Write-Host "      .\DefectDetector.exe --no-frontend"
Write-Host "      .\DefectDetector.exe --config config\config.yaml"
