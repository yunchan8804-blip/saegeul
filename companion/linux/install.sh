#!/usr/bin/env bash
# SPDX-License-Identifier: LGPL-2.1-or-later
# SPDX-FileCopyrightText: Copyright 2026 Yun Chan

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
INSTALL_DIR="${HOME}/.local/share/saegeul/companion"
SYSTEMD_USER_DIR="${HOME}/.config/systemd/user"
SERVICE_PATH="${SYSTEMD_USER_DIR}/saegeul-companion.service"

echo "========================================================"
echo "  Saegeul AI Companion Installer for Linux"
echo "  새글 키보드 Linux AI 연결 컴패니언 자동 설치기"
echo "========================================================"
echo

mkdir -p "${INSTALL_DIR}" "${SYSTEMD_USER_DIR}"

PYTHON_BIN="$(which python3 || true)"
if [[ -z "${PYTHON_BIN}" ]]; then
    echo "[!] Error: python3 is required. Please install it using your package manager (apt, dnf, pacman, etc.)."
    exit 1
fi

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

echo "[*] Creating systemd user service: ${SERVICE_PATH}"
cat <<EOF > "${SERVICE_PATH}"
[Unit]
Description=Saegeul AI Companion Gateway for Android Keyboard
After=network.target

[Service]
Type=simple
ExecStart=${PYTHON_BIN} ${INSTALL_DIR}/ai-provider-companion.py --gateway-port 9211 --tailscale-https-port 9210
Restart=always
RestartSec=5s

[Install]
WantedBy=default.target
EOF

if command -v systemctl &>/dev/null; then
    echo "[*] Reloading and enabling systemd user service..."
    systemctl --user daemon-reload
    systemctl --user enable --now saegeul-companion.service
    echo
    echo "========================================================"
    echo "  [SUCCESS] Saegeul AI Companion active via systemd!"
    echo "  상태 확인: systemctl --user status saegeul-companion"
    echo "========================================================"
else
    echo "[*] systemd not detected. Run manually with: ${INSTALL_DIR}/ai-provider-companion.py"
fi
