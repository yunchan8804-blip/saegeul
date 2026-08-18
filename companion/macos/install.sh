#!/usr/bin/env bash
# SPDX-License-Identifier: LGPL-2.1-or-later
# SPDX-FileCopyrightText: Copyright 2026 Yun Chan

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
INSTALL_DIR="${HOME}/.saegeul/companion"
PLIST_PATH="${HOME}/Library/LaunchAgents/net.chanpaca.saegeul.companion.plist"
LOG_DIR="${HOME}/.saegeul/logs"

echo "========================================================"
echo "  Saegeul AI Companion Installer for macOS"
echo "  새글 키보드 Mac AI 연결 컴패니언 자동 설치기"
echo "========================================================"
echo

mkdir -p "${INSTALL_DIR}" "${LOG_DIR}" "$(dirname "${PLIST_PATH}")"

# Find Python3
PYTHON_BIN="$(which python3 || true)"
if [[ -z "${PYTHON_BIN}" ]]; then
    echo "[!] Error: python3 is required. Please install it via Homebrew or official installer."
    exit 1
fi

# Locate companion script
SOURCE_SCRIPT=""
if [[ -f "${SCRIPT_DIR}/../../scripts/ai-provider-companion.py" ]]; then
    SOURCE_SCRIPT="${SCRIPT_DIR}/../../scripts/ai-provider-companion.py"
elif [[ -f "${SCRIPT_DIR}/ai-provider-companion.py" ]]; then
    SOURCE_SCRIPT="${SCRIPT_DIR}/ai-provider-companion.py"
elif [[ -f "${INSTALL_DIR}/ai-provider-companion.py" ]]; then
    SOURCE_SCRIPT="${INSTALL_DIR}/ai-provider-companion.py"
fi

if [[ -z "${SOURCE_SCRIPT}" ]]; then
    echo "[!] Error: Could not locate ai-provider-companion.py"
    exit 1
fi

cp -f "${SOURCE_SCRIPT}" "${INSTALL_DIR}/ai-provider-companion.py"
chmod +x "${INSTALL_DIR}/ai-provider-companion.py"

echo "[*] Generating macOS LaunchAgent plist at: ${PLIST_PATH}"
cat <<EOF > "${PLIST_PATH}"
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
    <key>Label</key>
    <string>net.chanpaca.saegeul.companion</string>
    <key>ProgramArguments</key>
    <array>
        <string>${PYTHON_BIN}</string>
        <string>${INSTALL_DIR}/ai-provider-companion.py</string>
        <string>--gateway-port</string>
        <string>9211</string>
        <string>--tailscale-https-port</string>
        <string>9210</string>
    </array>
    <key>RunAtLoad</key>
    <true/>
    <key>KeepAlive</key>
    <dict>
        <key>SuccessfulExit</key>
        <false/>
    </dict>
    <key>StandardOutPath</key>
    <string>${LOG_DIR}/companion.log</string>
    <key>StandardErrorPath</key>
    <string>${LOG_DIR}/companion.err.log</string>
</dict>
</plist>
EOF

# Unload previous instance if loaded
launchctl unload "${PLIST_PATH}" 2>/dev/null || true

# Load LaunchAgent
echo "[*] Starting Saegeul AI Companion LaunchAgent service..."
launchctl load "${PLIST_PATH}"

echo
echo "========================================================"
echo "  [SUCCESS] Saegeul AI Companion installed on macOS!"
echo "  로그 파일 위치: ${LOG_DIR}/companion.log"
echo "========================================================"
