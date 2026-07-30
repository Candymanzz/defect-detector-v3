@echo off
REM Rebuild DefectDetector.exe (no NuGet) into repo root.
set CSC=%WINDIR%\Microsoft.NET\Framework64\v4.0.30319\csc.exe
if not exist "%CSC%" (
  echo csc.exe not found: %CSC%
  exit /b 1
)
"%CSC%" /nologo /target:exe /optimize+ /out:"%~dp0..\DefectDetector.exe" "%~dp0Program.cs"
if errorlevel 1 exit /b 1
echo Built: %~dp0..\DefectDetector.exe
