# SPDX-License-Identifier: LGPL-2.1-or-later
# SPDX-FileCopyrightText: Copyright 2026 Yun Chan
import ctypes
import sys
import time
from pathlib import Path

import uiautomation as auto

sys.path.insert(0, str(Path(__file__).resolve().parent))
import play_uia as play

SW_MINIMIZE = 6
user32 = ctypes.windll.user32
ROOT = Path(r"D:\workspace\fcitx5-android")
OUT = Path(sys.argv[1])
icon = ROOT / "app/src/main/play/listings/ko-KR/graphics/icon/icon.png"

for w in auto.GetRootControl().GetChildren():
    cls = w.ClassName or ""
    name = w.Name or ""
    if cls == "SDL_app" or "Steam" in name:
        print("MINIMIZE", repr(name), cls)
        user32.ShowWindow(w.NativeWindowHandle, SW_MINIMIZE)

chrome = play.find_saegeul_chrome()
play.focus(chrome, maximize=True)
time.sleep(0.5)

buttons = []
for c, _d in play.walk(chrome, max_depth=24):
    if c.ControlTypeName == "ButtonControl" and (c.Name or "") == "애셋 추가":
        buttons.append(c)
print("ASSET_BUTTONS", len(buttons))
for i, b in enumerate(buttons):
    print(i, b.BoundingRectangle)

if not buttons:
    raise SystemExit("NO_ASSET_BUTTON")

try:
    buttons[0].GetInvokePattern().Invoke()
except Exception as exc:
    print("INVOKE_FAIL", exc)
    buttons[0].Click()
time.sleep(1.5)
play.screenshot(chrome, OUT / "after-asset-click.png")
ok = play.upload_via_file_dialog(icon, wait_dialog=8.0)
print("UPLOAD_ICON", ok, icon)
time.sleep(3.0)
play.screenshot(chrome, OUT / "after-icon.png")
print("TITLE", chrome.Name)
