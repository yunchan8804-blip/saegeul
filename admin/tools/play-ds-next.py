# SPDX-License-Identifier: LGPL-2.1-or-later
# SPDX-FileCopyrightText: Copyright 2026 Yun Chan
import sys
import time
from pathlib import Path

import uiautomation as auto

sys.path.insert(0, str(Path(__file__).resolve().parent))
import play_uia as play

OUT = Path(sys.argv[1])
name = sys.argv[2] if len(sys.argv) > 2 else "step"
chrome = None
for w in auto.GetRootControl().GetChildren():
    if w.ClassName == "Chrome_WidgetWin_1" and "새글" in (w.Name or ""):
        chrome = w
        break
play.focus(chrome, maximize=True)
auto.Click(2507, 1356)  # 다음
time.sleep(2.5)
print("NOW", chrome.Name)
play.screenshot(chrome, OUT / f"{name}.png")
for c, d in play.walk(chrome, max_depth=22):
    n = c.Name or ""
    if not n or len(n) > 140:
        continue
    if c.ControlTypeName in (
        "RadioButtonControl",
        "CheckBoxControl",
        "ButtonControl",
        "ComboBoxControl",
    ) or any(
        k in n
        for k in ("예", "아니요", "다음", "수집", "공유", "판매", "암호화", "삭제", "위치", "검색", "음성", "ID")
    ):
        r = c.BoundingRectangle
        if r.top > 150:
            print(d, c.ControlTypeName, repr(n[:110]), r)
