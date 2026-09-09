# SPDX-License-Identifier: LGPL-2.1-or-later
# SPDX-FileCopyrightText: Copyright 2026 Yun Chan
import sys
import time
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import play_uia as play

ROOT = Path(r"D:\workspace\fcitx5-android")
OUT = Path(sys.argv[1])
shots = [
    ROOT / "app/src/main/play/listings/ko-KR/graphics/phone-screenshots" / name
    for name in (
        "02-settings.png",
        "03-input-methods.png",
        "04-privacy.png",
        "05-about.png",
    )
]

chrome = play.find_saegeul_chrome()
play.focus(chrome, maximize=True)

for shot in shots:
    clicked = False
    for c, _d in play.walk(chrome, max_depth=24):
        if c.ControlTypeName == "ButtonControl" and (c.Name or "") == "업로드":
            c.Click()
            clicked = True
            break
    print("UPLOAD_CLICK", shot.name, clicked)
    time.sleep(0.8)
    ok = play.upload_via_file_dialog(shot, wait_dialog=8.0)
    print("RESULT", shot.name, ok)
    time.sleep(2.5)

play.screenshot(chrome, OUT / "shots-uploaded.png")
print("TITLE", chrome.Name)
