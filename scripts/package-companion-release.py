#!/usr/bin/env python3
# SPDX-License-Identifier: LGPL-2.1-or-later
# SPDX-FileCopyrightText: Copyright 2026 Yun Chan

"""Packages the Saegeul AI Companion installers for Windows, macOS, and Linux into release archives."""

from __future__ import annotations

import os
import shutil
import tarfile
import tempfile
import zipfile
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parent.parent
COMPANION_SRC = REPO_ROOT / "scripts" / "ai-provider-companion.py"
COMPANION_DIR = REPO_ROOT / "companion"
DIST_DIR = REPO_ROOT / "dist" / "companion"


def package_windows(dist_dir: Path) -> Path:
    archive_path = dist_dir / "saegeul-companion-windows.zip"
    with tempfile.TemporaryDirectory() as tmp:
        pkg_dir = Path(tmp) / "saegeul-companion-windows"
        pkg_dir.mkdir()
        shutil.copy2(COMPANION_SRC, pkg_dir / "ai-provider-companion.py")
        for file in (COMPANION_DIR / "windows").glob("*"):
            if file.is_file():
                shutil.copy2(file, pkg_dir / file.name)
        # Add Readme
        (pkg_dir / "README.txt").write_text(
            "=== Saegeul AI Companion for Windows ===\n\n"
            "1. Run 'install.bat' to install and start the background companion.\n"
            "2. Make sure Tailscale is connected on both PC and your Android phone.\n"
            "3. On your phone in Saegeul Settings > AI > Connect PC, approve your computer.\n"
            "4. To run interactively: run 'start.bat'\n"
            "5. To uninstall: run 'uninstall.bat'\n",
            encoding="utf-8",
        )
        with zipfile.ZipFile(archive_path, "w", zipfile.ZIP_DEFLATED) as zf:
            for root, _, files in os.walk(pkg_dir):
                for f in files:
                    full_path = Path(root) / f
                    arcname = full_path.relative_to(Path(tmp))
                    zf.write(full_path, arcname)
    print(f"[+] Created Windows package: {archive_path} ({archive_path.stat().st_size:,} bytes)")
    return archive_path


def package_macos(dist_dir: Path) -> Path:
    archive_path = dist_dir / "saegeul-companion-macos.tar.gz"
    with tempfile.TemporaryDirectory() as tmp:
        pkg_dir = Path(tmp) / "saegeul-companion-macos"
        pkg_dir.mkdir()
        shutil.copy2(COMPANION_SRC, pkg_dir / "ai-provider-companion.py")
        for file in (COMPANION_DIR / "macos").glob("*"):
            if file.is_file():
                shutil.copy2(file, pkg_dir / file.name)
        (pkg_dir / "README.txt").write_text(
            "=== Saegeul AI Companion for macOS ===\n\n"
            "1. Double-click 'install.command' or run './install.sh' in Terminal.\n"
            "2. Make sure Tailscale is connected on both Mac and your Android phone.\n"
            "3. On your phone in Saegeul Settings > AI > Connect PC, approve your Mac.\n"
            "4. To uninstall: run './uninstall.sh'\n",
            encoding="utf-8",
        )
        with tarfile.open(archive_path, "w:gz") as tar:
            tar.add(pkg_dir, arcname="saegeul-companion-macos")
    print(f"[+] Created macOS package: {archive_path} ({archive_path.stat().st_size:,} bytes)")
    return archive_path


def package_linux(dist_dir: Path) -> Path:
    archive_path = dist_dir / "saegeul-companion-linux.tar.gz"
    with tempfile.TemporaryDirectory() as tmp:
        pkg_dir = Path(tmp) / "saegeul-companion-linux"
        pkg_dir.mkdir()
        shutil.copy2(COMPANION_SRC, pkg_dir / "ai-provider-companion.py")
        for file in (COMPANION_DIR / "linux").glob("*"):
            if file.is_file():
                shutil.copy2(file, pkg_dir / file.name)
        (pkg_dir / "README.txt").write_text(
            "=== Saegeul AI Companion for Linux ===\n\n"
            "1. Run './install.sh' to install and register systemd user service.\n"
            "2. Make sure Tailscale is connected on both Linux and your Android phone.\n"
            "3. On your phone in Saegeul Settings > AI > Connect PC, approve your computer.\n"
            "4. Check status: systemctl --user status saegeul-companion\n"
            "5. To uninstall: run './uninstall.sh'\n",
            encoding="utf-8",
        )
        with tarfile.open(archive_path, "w:gz") as tar:
            tar.add(pkg_dir, arcname="saegeul-companion-linux")
    print(f"[+] Created Linux package: {archive_path} ({archive_path.stat().st_size:,} bytes)")
    return archive_path


def package_all_in_one(dist_dir: Path) -> Path:
    archive_path = dist_dir / "saegeul-companion-all-platforms.zip"
    with tempfile.TemporaryDirectory() as tmp:
        root_pkg = Path(tmp) / "saegeul-companion"
        root_pkg.mkdir()
        shutil.copy2(COMPANION_SRC, root_pkg / "ai-provider-companion.py")
        shutil.copytree(COMPANION_DIR / "windows", root_pkg / "windows")
        shutil.copytree(COMPANION_DIR / "macos", root_pkg / "macos")
        shutil.copytree(COMPANION_DIR / "linux", root_pkg / "linux")
        (root_pkg / "README.md").write_text(
            "# Saegeul AI Companion (새글 AI 컴패니언)\n\n"
            "Connect your Android Saegeul keyboard to your PC's local Codex / Claude Code CLI.\n\n"
            "## Installation by OS\n\n"
            "- **Windows**: Open `windows/` folder and double-click `install.bat`\n"
            "- **macOS**: Open `macos/` folder and double-click `install.command`\n"
            "- **Linux**: Open `linux/` folder and run `./install.sh`\n\n"
            "## Requirements\n\n"
            "- Python 3.9+\n"
            "- [Tailscale](https://tailscale.com/) installed and logged into same Tailnet on PC and Phone\n",
            encoding="utf-8",
        )
        with zipfile.ZipFile(archive_path, "w", zipfile.ZIP_DEFLATED) as zf:
            for root, _, files in os.walk(root_pkg):
                for f in files:
                    full_path = Path(root) / f
                    arcname = full_path.relative_to(Path(tmp))
                    zf.write(full_path, arcname)
    print(f"[+] Created All-in-One package: {archive_path} ({archive_path.stat().st_size:,} bytes)")
    return archive_path


def main():
    DIST_DIR.mkdir(parents=True, exist_ok=True)
    print("[*] Packaging Saegeul AI Companion across OS platforms...")
    package_windows(DIST_DIR)
    package_macos(DIST_DIR)
    package_linux(DIST_DIR)
    package_all_in_one(DIST_DIR)
    print("\n[SUCCESS] All companion packages created successfully in:", DIST_DIR)


if __name__ == "__main__":
    main()
