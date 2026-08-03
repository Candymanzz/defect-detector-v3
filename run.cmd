@echo off
cd /d "%~dp0"
if exist "%~dp0DefectDetector.exe" (
  "%~dp0DefectDetector.exe" %*
  exit /b %ERRORLEVEL%
)
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0run.ps1" %*
