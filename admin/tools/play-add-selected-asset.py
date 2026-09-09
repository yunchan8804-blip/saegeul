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
# Footer of the asset library: 추가 applies the selected library item to the drop zone.
clicked = None
for c, _d in play.walk(chrome, max_depth=24):
    if c.ControlTypeName == "ButtonControl" and (c.Name or "") == "추가":
        print("FOUND_ADD", c.BoundingRectangle)
        c.Click()
        clicked = c
        break
print("CLICKED", bool(clicked))
time.sleep(2.0)
play.screenshot(chrome, OUT / "after-add-icon.png")
print("TITLE", chrome.Name)
