# SPDX-License-Identifier: LGPL-2.1-or-later
# SPDX-FileCopyrightText: Copyright 2026 Yun Chan
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import play_uia as play

out = Path(sys.argv[1])
path = sys.argv[2] if len(sys.argv) > 2 else "/store-listing"
keys = sys.argv[3].split("|") if len(sys.argv) > 3 else [
    "스토어", "등록", "아이콘", "스크린", "설명", "저장", "그래픽",
    "휴대", "태블릿", "폼", "기기", "데이터", "광고", "정부", "금융", "건강",
    "다음", "예", "아니요", "업로드", "삭제", "Chrome", "XR", "전화",
]
url = path if path.startswith("http") else play.BASE + path
chrome = play.find_saegeul_chrome()
print("USING", chrome.Name, chrome.BoundingRectangle)
play.focus(chrome, maximize=True)
play.goto(chrome, url, wait=5.0)
print("TITLE", chrome.Name)
play.screenshot(chrome, out / "page.png")
play.dump_interesting(chrome, keys)
