# SPDX-License-Identifier: LGPL-2.1-or-later
# SPDX-FileCopyrightText: Copyright 2026 Yun Chan
import sys
import time
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import play_uia as play

OUT = Path(sys.argv[1])
chrome = play.find_saegeul_chrome()
play.focus(chrome, maximize=True)
time.sleep(0.3)

clicked = play.click_contains(chrome, "휴대전화, 태블릿", "ButtonControl", timeout=3)
print("DROPDOWN", bool(clicked))
time.sleep(1.0)
play.screenshot(chrome, OUT / "dropdown.png")
print("AFTER_OPEN")
for c, d in play.walk(chrome, max_depth=24):
    n = c.Name or ""
    if not n:
        continue
    if any(k in n for k in ("휴대", "태블릿", "Chrome", "XR", "Wear", "TV", "체크", "선택", "폼")):
        print(d, c.ControlTypeName, repr(n[:100]), c.BoundingRectangle)
