#!/usr/bin/env bash
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
echo "Starting Saegeul AI Companion in interactive mode..."
if [[ -f "${SCRIPT_DIR}/ai-provider-companion.py" ]]; then
    python3 "${SCRIPT_DIR}/ai-provider-companion.py" --gateway-port 9211 --tailscale-https-port 9210
elif [[ -f "${HOME}/.local/share/saegeul/companion/ai-provider-companion.py" ]]; then
    python3 "${HOME}/.local/share/saegeul/companion/ai-provider-companion.py" --gateway-port 9211 --tailscale-https-port 9210
else
    echo "[!] Could not locate ai-provider-companion.py"
fi
