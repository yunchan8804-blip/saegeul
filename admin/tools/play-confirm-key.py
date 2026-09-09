import ctypes
import sys
import time
from pathlib import Path

import uiautomation as auto
from PIL import ImageGrab

out = Path(sys.argv[1])
user32 = ctypes.windll.user32
ALT = 0x12


def focus(win):
    hwnd = win.NativeWindowHandle
    user32.keybd_event(ALT, 0, 0, 0)
    user32.SetForegroundWindow(hwnd)
    user32.keybd_event(ALT, 0, 2, 0)
    win.SetActive()
    time.sleep(0.4)


chrome = None
for w in auto.GetRootControl().GetChildren():
    if w.ClassName == "Chrome_WidgetWin_1" and "키" in (w.Name or ""):
        chrome = w
print("USING", chrome.Name)
focus(chrome)
btn = chrome.ButtonControl(Name="확인")
print("confirm", btn.Exists(2), btn.BoundingRectangle)
if btn.Exists(0.5):
    btn.Click()
    time.sleep(2.5)
focus(chrome)
r = chrome.BoundingRectangle
img = ImageGrab.grab(
    bbox=(max(r.left, 0), max(r.top, 0), r.right, r.bottom), all_screens=True
)
img.save(out / "play-key-confirmed.png")
print("title", chrome.Name, "fg", hex(user32.GetForegroundWindow()))
for c, d in auto.WalkControl(chrome, maxDepth=20):
    n = c.Name or ""
    if n and any(k in n for k in ["확인", "등록됨", "활성", "임시", "완료", "지문", "상태"]):
        if len(n) < 90:
            print(d, c.ControlTypeName, n)
