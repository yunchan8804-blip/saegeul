# SPDX-License-Identifier: LGPL-2.1-or-later
# SPDX-FileCopyrightText: Copyright 2026 Yun Chan
import sys
import time
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import play_uia as play

OUT = Path(sys.argv[1])
url = sys.argv[2]
chrome = None
import uiautomation as auto

skip = ("ChatGPT", "ZCode", "about:blank", "E2E QA", "Bitwarden")
for w in auto.GetRootControl().GetChildren():
    if w.ClassName != "Chrome_WidgetWin_1":
        continue
    n = w.Name or ""
    if any(s in n for s in skip):
        continue
    print("CAND", repr(n), w.BoundingRectangle)
    chrome = w
    if any(k in n for k in ("새글", "AdBlock", "4saved", "Installation", "데이터", "고급 설정", "출시")):
        break

print("USING", chrome.Name)
play.focus(chrome, maximize=True)
play.goto(chrome, url, wait=6.0)
print("NOW", chrome.Name)
play.screenshot(chrome, OUT / "page.png")
