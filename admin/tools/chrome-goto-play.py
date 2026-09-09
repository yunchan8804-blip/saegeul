import sys
import time
from pathlib import Path

import uiautomation as auto
from PIL import ImageGrab

out = Path(sys.argv[1] if len(sys.argv) > 1 else ".")
url = sys.argv[2] if len(sys.argv) > 2 else "https://play.google.com/console/developers/"
out.mkdir(parents=True, exist_ok=True)

auto.SetGlobalSearchTimeout(3.0)
windows = []
root = auto.GetRootControl()
for w in root.GetChildren():
    if w.ClassName != "Chrome_WidgetWin_1":
        continue
    name = w.Name or ""
    if name in ("Bitwarden", "ZCode", "about:blank - Chrome"):
        continue
    windows.append(w)
    print("WINDOW", repr(name), w.BoundingRectangle)

if not windows:
    print("NO_USER_CHROME")
    sys.exit(2)

chrome = windows[0]
for w in windows:
    if "확장" in (w.Name or "") or "Chrome" in (w.Name or ""):
        chrome = w
        break

print("USING", repr(chrome.Name))
chrome.SetActive()
time.sleep(0.4)

omnibox = chrome.EditControl(Name="주소창 및 검색창")
if not omnibox.Exists(1):
    omnibox = chrome.EditControl(LocalizedControlType="edit")
print("OMNIBOX", omnibox.Exists(0.5), repr(omnibox.Name), repr(omnibox.GetValuePattern().Value if omnibox.Exists(0.2) else None))

if omnibox.Exists(0.5):
    omnibox.GetValuePattern().SetValue(url)
    omnibox.SendKeys("{Enter}")
else:
    auto.SendKeys("{Ctrl}l")
    time.sleep(0.2)
    auto.SendKeys(url, waitTime=0.05)
    auto.SendKeys("{Enter}")

time.sleep(3.5)
print("TITLE_NOW", repr(chrome.Name))
rect = chrome.BoundingRectangle
img = ImageGrab.grab(bbox=(rect.left, rect.top, rect.right, rect.bottom), all_screens=True)
img.save(out / "play-console-now.png")
print("SHOT", out / "play-console-now.png")
