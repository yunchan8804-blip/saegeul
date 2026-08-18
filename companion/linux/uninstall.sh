#!/usr/bin/env bash
# SPDX-License-Identifier: LGPL-2.1-or-later
# SPDX-FileCopyrightText: Copyright 2026 Yun Chan

set -euo pipefail

SERVICE_PATH="${HOME}/.config/systemd/user/saegeul-companion.service"
INSTALL_DIR="${HOME}/.local/share/saegeul/companion"

echo "[*] Stopping and disabling Saegeul AI Companion systemd service..."
if command -v systemctl &>/dev/null; then
    systemctl --user stop saegeul-companion.service 2>/dev/null || true
    systemctl --user disable saegeul-companion.service 2>/dev/null || true
fi

rm -f "${SERVICE_PATH}"
if command -v systemctl &>/dev/null; then
    systemctl --user daemon-reload
fi

rm -rf "${INSTALL_DIR}"

echo "[SUCCESS] Saegeul AI Companion uninstalled from Linux."
