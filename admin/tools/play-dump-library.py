# SPDX-License-Identifier: LGPL-2.1-or-later
# SPDX-FileCopyrightText: Copyright 2026 Yun Chan
import sys
import time
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import play_uia as play

OUT = Path(sys.argv[1])
time.sleep(2)
chrome = play.find_saegeul_chrome()
play.focus(chrome, maximize=True)
play.screenshot(chrome, OUT / "library-now.png")
print("TITLE", chrome.Name)
for c, d in play.walk(chrome, max_depth=24):
    n = c.Name or ""
    if not n:
        continue
    if any(k in n.lower() for k in ("png", "jpg", "feature", "icon", "keyboard", "추가", "업로드", "사용 가능", "자르기")):
        if len(n) < 140:
            print(d, c.ControlTypeName, repr(n), c.BoundingRectangle)
