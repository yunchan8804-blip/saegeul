import ctypes
import sys
import time
from pathlib import Path

import uiautomation as auto
from PIL import ImageGrab

out = Path(sys.argv[1])
user32 = ctypes.windll.user32
ALT = 0x12

chrome = None
for w in auto.GetRootControl().GetChildren():
    if w.ClassName == "Chrome_WidgetWin_1" and "Chrome" in (w.Name or ""):
        if w.Name not in ("Bitwarden", "ZCode", "about:blank - Chrome"):
            chrome = w
            print("CAND", w.Name)
print("USING", chrome.Name)
hwnd = chrome.NativeWindowHandle
user32.keybd_event(ALT, 0, 0, 0)
user32.SetForegroundWindow(hwnd)
user32.keybd_event(ALT, 0, 2, 0)
chrome.SetActive()
time.sleep(0.3)

home = chrome.ButtonControl(Name="홈")
print("home", home.Exists(2), home.BoundingRectangle)
home.Click()
time.sleep(3)
print("title", chrome.Name)
r = chrome.BoundingRectangle
img = ImageGrab.grab(
    bbox=(max(r.left, 0), max(r.top, 0), r.right, r.bottom), all_screens=True
)
img.save(out / "play-home-2.png")
print("saved", img.size)
