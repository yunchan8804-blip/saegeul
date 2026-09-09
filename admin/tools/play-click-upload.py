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
icon = ROOT / "app/src/main/play/listings/ko-KR/graphics/icon/icon.png"

chrome = play.find_saegeul_chrome()
play.focus(chrome, maximize=True)
time.sleep(0.3)

clicked = play.click_name(chrome, "업로드")
print("CLICK_UPLOAD", bool(clicked))
if not clicked:
    auto.Click(2297, 1296)
    time.sleep(0.8)
play.screenshot(chrome, OUT / "after-upload-btn.png")
ok = play.upload_via_file_dialog(icon, wait_dialog=8.0)
print("DIALOG", ok)
time.sleep(3)
play.screenshot(chrome, OUT / "after-icon2.png")
print("TITLE", chrome.Name)
