# SPDX-License-Identifier: LGPL-2.1-or-later
# SPDX-FileCopyrightText: Copyright 2026 Yun Chan
import sys
import time
from pathlib import Path

import uiautomation as auto

sys.path.insert(0, str(Path(__file__).resolve().parent))
import play_uia as play

ROOT = Path(r"D:\workspace\fcitx5-android")
OUT = Path(sys.argv[1])
short = (ROOT / "app/src/main/play/listings/ko-KR/short-description.txt").read_text(
    encoding="utf-8"
).strip()

chrome = play.find_saegeul_chrome()
play.focus(chrome, maximize=True)
time.sleep(0.3)

# Title field (confirmed at 1100,640)
auto.Click(1100, 640)
time.sleep(0.2)
auto.SetClipboardText("새글")
auto.SendKeys("{Ctrl}a")
time.sleep(0.05)
auto.SendKeys("{Ctrl}v")
time.sleep(0.3)

# Short description is the empty single-line box under the title.
auto.Click(1100, 720)
time.sleep(0.2)
play.paste_into_focused(short)
time.sleep(0.3)

play.screenshot(chrome, OUT / "after-fix-text.png")
print("TITLE_NOW", chrome.Name)
print("SHORT_LEN", len(short))
