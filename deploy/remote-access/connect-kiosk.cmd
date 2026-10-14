@echo off
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0connect-kiosk.ps1" %*
if errorlevel 1 pause
