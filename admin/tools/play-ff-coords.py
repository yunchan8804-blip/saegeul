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
auto.Click(1909, 191)
time.sleep(0.5)
auto.Click(1910, 271)
time.sleep(2.5)
play.screenshot(chrome, OUT / "manage2.png")
print("TITLE", chrome.Name)
for c, d in play.walk(chrome, max_depth=24):
    n = c.Name or ""
    if n and any(
        k in n
        for k in ("휴대", "태블릿", "Chrome OS", "XR", "Wear", "TV", "저장", "폼", "사용 설정", "사용 중지")
    ):
        print(d, c.ControlTypeName, repr(n[:120]), c.BoundingRectangle)
