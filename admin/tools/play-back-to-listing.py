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
print("BEFORE", chrome.Name)
auto.SendKeys("{Ctrl}1")
time.sleep(1.0)
print("AFTER_CTRL1", chrome.Name)
if "등록정보" not in (chrome.Name or "") and "새글" not in (chrome.Name or ""):
    play.goto(chrome, play.BASE + "/main-store-listing", wait=5.0)
    print("NAV", chrome.Name)
play.screenshot(chrome, OUT / "back-listing.png")
play.dump_interesting(
    chrome,
    ["애셋", "저장", "아이콘", "설명", "그래픽", "스크린", "새글", "업로드"],
)
