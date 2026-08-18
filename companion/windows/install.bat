@echo off
setlocal enabledelayedexpansion

echo ========================================================
echo   Saegeul AI Companion Installer for Windows
echo   새글 키보드 PC AI 연결 컴패니언 자동 설치기
echo ========================================================
echo.

set "INSTALL_DIR=%LOCALAPPDATA%\Saegeul\companion"
set "SCRIPT_DIR=%~dp0"

echo [*] Installing Saegeul AI Companion to: %INSTALL_DIR%
if not exist "%INSTALL_DIR%" mkdir "%INSTALL_DIR%"

copy /Y "%SCRIPT_DIR%..\..\scripts\ai-provider-companion.py" "%INSTALL_DIR%\ai-provider-companion.py" >nul 2>&1
if not exist "%INSTALL_DIR%\ai-provider-companion.py" (
    copy /Y "%SCRIPT_DIR%ai-provider-companion.py" "%INSTALL_DIR%\ai-provider-companion.py" >nul 2>&1
)

if not exist "%INSTALL_DIR%\ai-provider-companion.py" (
    echo [!] Error: ai-provider-companion.py could not be found.
    pause
    exit /b 1
)

copy /Y "%SCRIPT_DIR%start.bat" "%INSTALL_DIR%\start.bat" >nul 2>&1
copy /Y "%SCRIPT_DIR%uninstall.bat" "%INSTALL_DIR%\uninstall.bat" >nul 2>&1

echo [*] Registering Windows Scheduled Task for auto-start...
powershell -NoProfile -ExecutionPolicy Bypass -File "%SCRIPT_DIR%install.ps1" -InstallDir "%INSTALL_DIR%"

if %ERRORLEVEL% EQU 0 (
    echo.
    echo ========================================================
    echo   [SUCCESS] Saegeul AI Companion installation complete!
    echo   새글 AI 컴패니언이 백그라운드에서 정상 실행 중입니다.
    echo ========================================================
) else (
    echo [!] Installation encountered a warning. Please check PowerShell permissions.
)

pause
