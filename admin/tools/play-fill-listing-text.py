# SPDX-License-Identifier: LGPL-2.1-or-later
# SPDX-FileCopyrightText: Copyright 2026 Yun Chan
"""Fill Korean store listing title/short/full description by clicking visible fields."""

from __future__ import annotations

import sys
import time
from pathlib import Path

import uiautomation as auto

sys.path.insert(0, str(Path(__file__).resolve().parent))
import play_uia as play

ROOT = Path(r"D:\workspace\fcitx5-android")
OUT = Path(sys.argv[1]) if len(sys.argv) > 1 else ROOT / "artifacts/play-listing/console/listing"
LIST = ROOT / "app/src/main/play/listings/ko-KR"
title = (LIST / "title.txt").read_text(encoding="utf-8").strip()
short = (LIST / "short-description.txt").read_text(encoding="utf-8").strip()
full = (LIST / "full-description.txt").read_text(encoding="utf-8").strip()
print("TITLE", title, len(title))
print("SHORT", short, len(short))
print("FULL_CHARS", len(full))

chrome = play.find_saegeul_chrome()
play.focus(chrome, maximize=True)
time.sleep(0.4)
play.screenshot(chrome, OUT / "before-fill.png")

# Click the empty short-description box. Coordinates come from the maximized
# Play Console screenshot: sidebar ~280px, field column starts ~960px.
# Window origin is slightly negative because of the maximized chrome frame.
r = chrome.BoundingRectangle
# Use document-relative clicks via screen coords that matched dump of 번역 관리 (766,310).
# Short description field is the empty single-line box under 앱 이름.
auto.Click(1100, 640)
time.sleep(0.3)
play.paste_into_focused(short)
time.sleep(0.4)
play.screenshot(chrome, OUT / "after-short.png")
print("AFTER_SHORT", chrome.Name)

auto.Click(1100, 820)
time.sleep(0.3)
play.paste_into_focused(full)
time.sleep(0.5)
play.screenshot(chrome, OUT / "after-full.png")
print("AFTER_FULL", chrome.Name)
print("done")
