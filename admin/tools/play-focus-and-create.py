import ctypes
import sys
import time
from pathlib import Path

import uiautomation as auto

out = Path(sys.argv[1])
user32 = ctypes.windll.user32

SW_RESTORE = 9
SW_SHOW = 5

chrome = None
for w in auto.GetRootControl().GetChildren():
    if w.ClassName == "Chrome_WidgetWin_1" and (w.Name or "").startswith("홈"):
        chrome = w
        break
print("USING", chrome.Name, hex(chrome.NativeWindowHandle), chrome.BoundingRectangle)
hwnd = chrome.NativeWindowHandle
user32.ShowWindow(hwnd, SW_RESTORE)
user32.ShowWindow(hwnd, SW_SHOW)
user32.BringWindowToTop(hwnd)
# Background processes need an input event before SetForegroundWindow succeeds.
ALT = 0x12
user32.keybd_event(ALT, 0, 0, 0)
user32.SetForegroundWindow(hwnd)
user32.keybd_event(ALT, 0, 2, 0)
chrome.SetActive()
time.sleep(0.8)
chrome.CaptureToImage(str(out / "play-focused.png"))
print("focused title", chrome.Name)
print("foreground", hex(user32.GetForegroundWindow()))
