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
    if w.ClassName == "Chrome_WidgetWin_1" and (w.Name or "").startswith("홈"):
        chrome = w
hwnd = chrome.NativeWindowHandle
user32.keybd_event(ALT, 0, 0, 0)
user32.SetForegroundWindow(hwnd)
user32.keybd_event(ALT, 0, 2, 0)
chrome.SetActive()
time.sleep(0.4)
auto.SendKeys("{F5}")
time.sleep(4)
print("title", chrome.Name, chrome.BoundingRectangle)
r = chrome.BoundingRectangle
img = ImageGrab.grab(bbox=(r.left, r.top, r.right, r.bottom), all_screens=True)
img.save(out / "play-reloaded.png")
print("saved", img.size)
