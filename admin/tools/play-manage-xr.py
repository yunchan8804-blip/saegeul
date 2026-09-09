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
clicked = play.click_name(chrome, "관리 Android XR")
print("CLICK_MANAGE", bool(clicked))
if not clicked:
    play.click_contains(chrome, "관리", "ButtonControl", timeout=2)
time.sleep(2.0)
play.screenshot(chrome, OUT / "xr.png")
print("TITLE", chrome.Name)
for c, d in play.walk(chrome, max_depth=24):
    n = c.Name or ""
    if not n:
        continue
    if any(k in n for k in ("XR", "사용", "중지", "활성", "삭제", "저장", "확인", "해제", "관리")):
        print(d, c.ControlTypeName, repr(n[:110]), c.BoundingRectangle)
