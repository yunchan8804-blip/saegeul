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
    if w.ClassName != "Chrome_WidgetWin_1":
        continue
    if w.Name in ("Bitwarden", "ZCode", "about:blank - Chrome"):
        continue
    if "Chrome" in (w.Name or ""):
        chrome = w
        print("CANDIDATE", w.Name)
print("USING", chrome.Name if chrome else None)
hwnd = chrome.NativeWindowHandle
user32.keybd_event(ALT, 0, 0, 0)
user32.SetForegroundWindow(hwnd)
user32.keybd_event(ALT, 0, 2, 0)
chrome.SetActive()
time.sleep(0.5)
print("fg", hex(user32.GetForegroundWindow()), "hwnd", hex(hwnd))
print("rect", chrome.BoundingRectangle)
r = chrome.BoundingRectangle
img = ImageGrab.grab(
    bbox=(max(r.left, 0), max(r.top, 0), r.right, r.bottom), all_screens=True
)
img.save(out / "play-current.png")
print("saved", img.size)
for c, d in auto.WalkControl(chrome, maxDepth=16):
    n = c.Name or ""
    if n and any(k in n for k in ["인증", "앱", "만들", "확인", "계속", "시작", "신분증", "계정"]):
        if len(n) < 80:
            print(d, c.ControlTypeName, n)
