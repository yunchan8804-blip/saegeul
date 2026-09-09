import sys
import time
from pathlib import Path

import uiautomation as auto
from PIL import ImageGrab

out = Path(sys.argv[1])
auto.SetGlobalSearchTimeout(4)

chrome = None
for w in auto.GetRootControl().GetChildren():
    if w.ClassName == "Chrome_WidgetWin_1" and (w.Name or "").startswith("홈"):
        chrome = w
        break
if chrome is None:
    for w in auto.GetRootControl().GetChildren():
        if w.ClassName == "Chrome_WidgetWin_1" and "Play" in (w.Name or ""):
            chrome = w
print("USING", chrome.Name if chrome else None)
chrome.SetActive()
time.sleep(0.3)

r = chrome.BoundingRectangle
# 앱 만들기 is on the 앱 1개 heading row, right-aligned with the search box.
points = [
    (int(r.left + r.width() * 0.88), int(r.top + 412)),
    (int(r.left + r.width() * 0.86), int(r.top + 400)),
    (int(r.left + r.width() * 0.84), int(r.top + 422)),
    (int(r.left + 2320), int(r.top + 408)),
]
for x, y in points:
    print("click", x, y)
    auto.Click(x, y)
    time.sleep(1.4)
    print(" title", chrome.Name)
    if chrome.Name and not chrome.Name.startswith("홈"):
        break

time.sleep(1)
r = chrome.BoundingRectangle
img = ImageGrab.grab(bbox=(r.left, r.top, r.right, r.bottom), all_screens=True)
img.save(out / "play-create-2.png")
print("final", chrome.Name)
