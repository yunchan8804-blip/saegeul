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
print("USING", chrome.Name, chrome.BoundingRectangle)
hwnd = chrome.NativeWindowHandle
user32.keybd_event(ALT, 0, 0, 0)
user32.SetForegroundWindow(hwnd)
user32.keybd_event(ALT, 0, 2, 0)
chrome.SetActive()
time.sleep(0.3)

r = chrome.BoundingRectangle
# 앱 만들기 on current 1706x1073 window, right of 앱 1개 heading
x = r.left + int(r.width() * 0.90)
y = r.top + int(r.height() * 0.42)
print("click", x, y)
auto.Click(x, y)
time.sleep(2.5)
print("title", chrome.Name)
r = chrome.BoundingRectangle
img = ImageGrab.grab(
    bbox=(max(r.left, 0), max(r.top, 0), r.right, r.bottom), all_screens=True
)
img.save(out / "play-create-app-form.png")
print("saved", img.size)
