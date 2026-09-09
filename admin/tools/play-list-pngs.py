# SPDX-License-Identifier: LGPL-2.1-or-later
# SPDX-FileCopyrightText: Copyright 2026 Yun Chan
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import play_uia as play

chrome = play.find_saegeul_chrome()
play.focus(chrome, maximize=True)
for c, d in play.walk(chrome, max_depth=24):
    n = c.Name or ""
    if ".png" in n.lower():
        print(d, c.ControlTypeName, repr(n[:120]), c.BoundingRectangle)
