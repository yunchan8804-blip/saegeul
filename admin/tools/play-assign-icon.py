# SPDX-License-Identifier: LGPL-2.1-or-later
# SPDX-FileCopyrightText: Copyright 2026 Yun Chan
import sys
import time
from pathlib import Path

import uiautomation as auto

sys.path.insert(0, str(Path(__file__).resolve().parent))
import play_uia as play

OUT = Path(sys.argv[1])
chrome = play.find_saegeul_chrome()
play.focus(chrome, maximize=True)
time.sleep(0.3)
print("TITLE", chrome.Name)
for c, d in play.walk(chrome, max_depth=24):
    n = c.Name or ""
    if not n:
        continue
    if any(k in n.lower() for k in ("icon", "png", "애셋", "추가", "적용", "선택", "그래픽")):
        if len(n) < 120:
            print(d, c.ControlTypeName, repr(n), c.BoundingRectangle)

clicked = play.click_contains(chrome, "icon.png", timeout=2.0)
print("CLICK_ICON", bool(clicked))
time.sleep(1.0)
play.screenshot(chrome, OUT / "after-assign-icon.png")
