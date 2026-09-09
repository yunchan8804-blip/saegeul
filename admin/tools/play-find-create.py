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
print("USING", chrome.Name)
chrome.SetActive()
time.sleep(0.3)

# Crop heading row so we can see 앱 만들기 pixels
r = chrome.BoundingRectangle
img = ImageGrab.grab(bbox=(r.left, r.top, r.right, r.bottom), all_screens=True)
# heading row roughly y=360-450 of screenshot
crop = img.crop((int(img.width * 0.55), 350, int(img.width * 0.98), 460))
crop.save(out / "play-heading-crop.png")
print("crop", crop.size)

# Chrome find bar
auto.SendKeys("{Ctrl}f")
time.sleep(0.6)
auto.SendKeys("앱 만들기")
time.sleep(0.8)
auto.SendKeys("{Enter}")
time.sleep(0.5)
auto.SendKeys("{Escape}")
time.sleep(0.3)
# After find, Enter on the highlighted match via click near find
# Click the find result - often the page scrolls/highlights the link.
# Try clicking the highlighted area: same heading row, right side
x = int(r.left + r.width() * 0.90)
y = int(r.top + 430)
print("click highlighted", x, y)
auto.Click(x, y)
time.sleep(2)
print("title", chrome.Name)
r = chrome.BoundingRectangle
img = ImageGrab.grab(bbox=(r.left, r.top, r.right, r.bottom), all_screens=True)
img.save(out / "play-after-find.png")
