@echo off
setlocal
echo ========================================================
echo   Saegeul AI Companion Uninstaller
echo ========================================================
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0uninstall.ps1"
pause
