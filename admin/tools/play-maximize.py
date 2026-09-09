import ctypes
import sys
import time
from pathlib import Path

import uiautomation as auto
from PIL import ImageGrab

out = Path(sys.argv[1])
user32 = ctypes.windll.user32
ALT = 0x12
SW_MAXIMIZE = 3

chrome = None
for w in auto.GetRootControl().GetChildren():
    if w.ClassName == "Chrome_WidgetWin_1" and (w.Name or "").startswith("홈"):
        chrome = w
hwnd = chrome.NativeWindowHandle
user32.keybd_event(ALT, 0, 0, 0)
user32.SetForegroundWindow(hwnd)
user32.keybd_event(ALT, 0, 2, 0)
user32.ShowWindow(hwnd, SW_MAXIMIZE)
chrome.SetActive()
time.sleep(1.2)
print("title", chrome.Name, chrome.BoundingRectangle)
r = chrome.BoundingRectangle
img = ImageGrab.grab(bbox=(max(r.left, 0), max(r.top, 0), r.right, r.bottom), all_screens=True)
img.save(out / "play-max.png")
print("saved", img.size)
