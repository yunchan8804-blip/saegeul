# SPDX-License-Identifier: LGPL-2.1-or-later
# SPDX-FileCopyrightText: Copyright 2026 Yun Chan
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import play_uia as play

out = Path(sys.argv[1])
chrome = play.find_saegeul_chrome()
play.focus(chrome, maximize=True)
print("TITLE", chrome.Name)
print("RECT", chrome.BoundingRectangle)
play.screenshot(chrome, out / "page.png")
print("EDITS")
for c, d in play.walk(chrome, max_depth=22):
    if c.ControlTypeName not in (
        "EditControl",
        "ButtonControl",
        "ComboBoxControl",
        "CheckBoxControl",
        "RadioButtonControl",
        "DocumentControl",
    ):
        continue
    n = c.Name or ""
    if len(n) > 160:
        n = n[:160] + "…"
    print(d, c.ControlTypeName, c.BoundingRectangle, repr(n))
