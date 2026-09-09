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
time.sleep(0.2)

auto.MoveTo(900, 900)
for _ in range(6):
    auto.WheelDown()
    time.sleep(0.12)
time.sleep(0.5)
play.screenshot(chrome, OUT / "phone-zone.png")

# Click the phone-screenshot 애셋 추가 (text nearby mentions 휴대전화).
target = None
for c, _d in play.walk(chrome, max_depth=24):
    if c.ControlTypeName == "ButtonControl" and (c.Name or "") == "애셋 추가":
        top = c.BoundingRectangle.top
        print("ASSET", c.BoundingRectangle)
        if 400 < top < 900:
            target = c
if target:
    print("PHONE_ZONE", target.BoundingRectangle)
    target.Click()
    time.sleep(0.6)
else:
    print("NO_VISIBLE_PHONE_ZONE")

shots = [
    "01-keyboard.png",
    "02-settings.png",
    "03-input-methods.png",
    "04-privacy.png",
    "05-about.png",
]
for name in shots:
    # Keep the phone zone selected by not clicking elsewhere first.
    found = False
    for c, _d in play.walk(chrome, max_depth=24):
        n = c.Name or ""
        if name in n and c.ControlTypeName == "GroupControl":
            print("SELECT", name, c.BoundingRectangle)
            c.Click()
            found = True
            break
    if not found:
        print("MISSING", name)
        continue
    time.sleep(0.4)
    added = False
    for c, _d in play.walk(chrome, max_depth=24):
        if c.ControlTypeName == "ButtonControl" and (c.Name or "") == "추가":
            c.Click()
            added = True
            print("ADDED", name)
            break
    if not added:
        print("NO_ADD", name)
    time.sleep(1.2)

play.screenshot(chrome, OUT / "after-phone-shots.png")
print("TITLE", chrome.Name)
