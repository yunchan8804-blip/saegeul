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

# Target the feature-graphic drop zone (second visible 애셋 추가 at ~y=967).
buttons = []
for c, _d in play.walk(chrome, max_depth=24):
    if c.ControlTypeName == "ButtonControl" and (c.Name or "") == "애셋 추가":
        buttons.append(c)
print("ASSET_BTNS", [(b.BoundingRectangle.top, b.BoundingRectangle.bottom) for b in buttons])
target = None
for b in buttons:
    top = b.BoundingRectangle.top
    if 900 < top < 1100:
        target = b
        break
if target is None and len(buttons) >= 2:
    target = buttons[1]
print("TARGET", target.BoundingRectangle if target else None)
if target:
    target.Click()
    time.sleep(0.8)

# Select feature-graphic.png in the library.
sel = play.click_contains(chrome, "feature-graphic.png", timeout=2.0)
print("SELECT", bool(sel))
time.sleep(0.5)

for c, _d in play.walk(chrome, max_depth=24):
    if c.ControlTypeName == "ButtonControl" and (c.Name or "") == "추가":
        print("ADD", c.BoundingRectangle)
        c.Click()
        break
time.sleep(2.0)
play.screenshot(chrome, OUT / "after-feature.png")
print("TITLE", chrome.Name)
