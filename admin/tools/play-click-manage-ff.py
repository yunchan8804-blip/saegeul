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
clicked = play.click_contains(chrome, "폼 팩터 관리", timeout=2)
print("CLICK", bool(clicked))
time.sleep(2.5)
play.screenshot(chrome, OUT / "manage.png")
print("TITLE", chrome.Name)
for c, d in play.walk(chrome, max_depth=24):
    n = c.Name or ""
    if not n:
        continue
    if any(k in n for k in ("휴대", "태블릿", "Chrome", "XR", "Wear", "TV", "저장", "사용", "설정", "폼", "체크")):
        print(d, c.ControlTypeName, repr(n[:110]), c.BoundingRectangle)
