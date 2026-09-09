# SPDX-License-Identifier: LGPL-2.1-or-later
# SPDX-FileCopyrightText: Copyright 2026 Yun Chan
import sys
import time
from pathlib import Path

import uiautomation as auto

sys.path.insert(0, str(Path(__file__).resolve().parent))
import play_uia as play

OUT = Path(sys.argv[1])
chrome = None
for w in auto.GetRootControl().GetChildren():
    if w.ClassName == "Chrome_WidgetWin_1" and (w.Name or "").startswith("홈"):
        chrome = w
        break
print("USING", chrome.Name)
play.focus(chrome, maximize=True)
clicked = play.click_contains(chrome, "앱 보기", timeout=3)
print("CLICK_VIEW", bool(clicked))
# There are two 앱 보기; first is 새글.
time.sleep(4.0)
print("NOW", chrome.Name)
play.screenshot(chrome, OUT / "app.png")
