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
play.click_name(chrome, "취소") or play.click_contains(chrome, "닫기", timeout=1)
time.sleep(0.6)
clicked = play.click_contains(chrome, "앱 이용 가능 여부", "TabItemControl", timeout=2)
print("TAB", bool(clicked))
if not clicked:
    auto.Click(680, 289)  # first tab approx
time.sleep(2.0)
play.screenshot(chrome, OUT / "availability.png")
print("TITLE", chrome.Name)
for c, d in play.walk(chrome, max_depth=24):
    n = c.Name or ""
    if n and any(k in n for k in ("휴대", "태블릿", "Chrome", "XR", "Wear", "TV", "사용", "기기", "가능", "저장")):
        print(d, c.ControlTypeName, repr(n[:110]), c.BoundingRectangle)
