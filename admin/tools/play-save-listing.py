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
time.sleep(0.2)
clicked = False
for c, _d in play.walk(chrome, max_depth=24):
    if c.ControlTypeName == "ButtonControl" and (c.Name or "") == "저장":
        print("SAVE", c.BoundingRectangle)
        c.Click()
        clicked = True
        break
print("CLICKED", clicked)
time.sleep(4.0)
play.screenshot(chrome, OUT / "after-save.png")
print("TITLE", chrome.Name)
play.dump_interesting(
    chrome,
    ["저장", "오류", "필수", "태블릿", "설명", "완료", "검토", "게시", "경고"],
)
