@echo off
cd /d "%~dp0"
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0open-local-inspection-test.ps1"
if errorlevel 1 (
  echo.
  echo Failed to open the local inspection interface.
  pause
)
