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
clicked = play.click_name(chrome, "나중에")
print("LATER", bool(clicked))
if not clicked:
    play.click_contains(chrome, "나중에", "ButtonControl", timeout=2)
time.sleep(1.0)
play.screenshot(chrome, OUT / "after-later.png")
print("TITLE", chrome.Name)
