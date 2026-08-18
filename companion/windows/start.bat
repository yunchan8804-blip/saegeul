@echo off
setlocal
echo Starting Saegeul AI Companion in interactive mode...
set "SCRIPT_DIR=%~dp0"
if exist "%SCRIPT_DIR%ai-provider-companion.py" (
    python "%SCRIPT_DIR%ai-provider-companion.py" --gateway-port 9211 --tailscale-https-port 9210
) else if exist "%LOCALAPPDATA%\Saegeul\companion\ai-provider-companion.py" (
    python "%LOCALAPPDATA%\Saegeul\companion\ai-provider-companion.py" --gateway-port 9211 --tailscale-https-port 9210
) else (
    echo [!] Could not locate ai-provider-companion.py
)
pause
