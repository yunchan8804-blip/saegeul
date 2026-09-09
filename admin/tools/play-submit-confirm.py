import ctypes
import time
from pathlib import Path
import sys

import uiautomation as auto
from PIL import ImageGrab

out = Path(sys.argv[1])
user32 = ctypes.windll.user32
ALT = 0x12

chrome = None
for w in auto.GetRootControl().GetChildren():
    if w.ClassName == "Chrome_WidgetWin_1" and "키" in (w.Name or ""):
        chrome = w
print("USING", chrome.Name)
hwnd = chrome.NativeWindowHandle
user32.keybd_event(ALT, 0, 0, 0)
user32.SetForegroundWindow(hwnd)
user32.keybd_event(ALT, 0, 2, 0)
chrome.SetActive()
time.sleep(0.3)

dlg = None
for c, d in auto.WalkControl(chrome, maxDepth=18):
    if c.ControlTypeName == "WindowControl" and "제출하시겠어요" in (c.Name or ""):
        dlg = c
        print("DLG", c.Name, c.BoundingRectangle)
        break
if dlg:
    btn = dlg.ButtonControl(Name="제출")
    print("dlg submit", btn.Exists(1), btn.BoundingRectangle)
    btn.Click()
else:
    print("NO_DLG")
time.sleep(4)
print("title", chrome.Name)
r = chrome.BoundingRectangle
img = ImageGrab.grab(
    bbox=(max(r.left, 0), max(r.top, 0), r.right, r.bottom), all_screens=True
)
img.save(out / "play-reg-done.png")
for c, d in auto.WalkControl(chrome, maxDepth=18):
    n = c.Name or ""
    if n and any(k in n for k in ["등록", "검토", "임시", "활성", "완료", "이메일", "상태"]):
        if len(n) < 110:
            print(d, c.ControlTypeName, n)
