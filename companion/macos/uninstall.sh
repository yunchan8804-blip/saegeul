#!/usr/bin/env bash
# SPDX-License-Identifier: LGPL-2.1-or-later
# SPDX-FileCopyrightText: Copyright 2026 Yun Chan

set -euo pipefail

PLIST_PATH="${HOME}/Library/LaunchAgents/net.chanpaca.saegeul.companion.plist"
INSTALL_DIR="${HOME}/.saegeul/companion"

echo "[*] Stopping Saegeul AI Companion on macOS..."
launchctl unload "${PLIST_PATH}" 2>/dev/null || true
rm -f "${PLIST_PATH}"

echo "[*] Removing companion files..."
rm -rf "${INSTALL_DIR}"

echo "[SUCCESS] Saegeul AI Companion uninstalled from macOS."
