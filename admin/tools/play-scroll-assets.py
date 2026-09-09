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
auto.MoveTo(900, 800)
for i in range(8):
    auto.WheelDown()
    time.sleep(0.15)
time.sleep(0.6)
play.screenshot(chrome, OUT / "scrolled.png")
print("TITLE", chrome.Name)
for c, d in play.walk(chrome, max_depth=24):
    n = c.Name or ""
    if c.ControlTypeName != "ButtonControl":
        continue
    if any(k in n for k in ("애셋", "업로드", "아이콘", "스크린", "저장", "그래픽")):
        print(d, n, c.BoundingRectangle)
