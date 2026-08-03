@echo off
REM Double-click / Start Menu friendly entry — same as DefectDetector.exe
cd /d "%~dp0"
if not exist "%~dp0DefectDetector.exe" (
  echo DefectDetector.exe not found. Build: powershell -File launcher\build-exe.ps1
  pause
  exit /b 1
)
"%~dp0DefectDetector.exe" %*
