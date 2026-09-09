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

# Re-select the feature graphic drop zone.
for c, _d in play.walk(chrome, max_depth=24):
    if c.ControlTypeName == "ButtonControl" and (c.Name or "") == "애셋 추가":
        if 900 < c.BoundingRectangle.top < 1100:
            c.Click()
            print("ZONE", c.BoundingRectangle)
            break
time.sleep(0.6)

# Select the library row.
for c, _d in play.walk(chrome, max_depth=24):
    n = c.Name or ""
    if "feature-graphic.png" in n and c.ControlTypeName in ("GroupControl", "ListItemControl"):
        print("ROW", c.ControlTypeName, c.BoundingRectangle, repr(n[:80]))
        c.Click()
        break
time.sleep(0.6)
play.screenshot(chrome, OUT / "feature-selected.png")

print("BUTTONS_AFTER_SELECT")
for c, _d in play.walk(chrome, max_depth=24):
    n = c.Name or ""
    if c.ControlTypeName == "ButtonControl" and n and c.BoundingRectangle.left >= 2100:
        print(repr(n[:80]), c.BoundingRectangle)
