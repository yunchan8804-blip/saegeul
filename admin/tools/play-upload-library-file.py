# SPDX-License-Identifier: LGPL-2.1-or-later
# SPDX-FileCopyrightText: Copyright 2026 Yun Chan
"""Upload a file into the Play Console asset library via the 업로드 button."""

from __future__ import annotations

import sys
import time
from pathlib import Path

import uiautomation as auto

sys.path.insert(0, str(Path(__file__).resolve().parent))
import play_uia as play

OUT = Path(sys.argv[1])
files = [Path(p) for p in sys.argv[2:]]
if not files:
    raise SystemExit("no files")

chrome = play.find_saegeul_chrome()
play.focus(chrome, maximize=True)
time.sleep(0.3)

clicked = False
for c, _d in play.walk(chrome, max_depth=24):
    if c.ControlTypeName == "ButtonControl" and (c.Name or "") == "업로드":
        print("UPLOAD_BTN", c.BoundingRectangle)
        c.Click()
        clicked = True
        break
print("CLICK_UPLOAD", clicked)
time.sleep(1.0)

# Multi-select: quoted paths in the filename box.
joined = " ".join(f'"{p.resolve()}"' for p in files)
dlg = auto.WindowControl(searchDepth=4, ClassName="#32770")
if not dlg.Exists(8):
    print("NO_FILE_DIALOG")
    play.screenshot(chrome, OUT / "no-dialog.png")
    raise SystemExit(2)
print("FILE_DIALOG", dlg.Name, dlg.BoundingRectangle)
edit = dlg.EditControl(Name="파일 이름(N):")
if not edit.Exists(1):
    edit = dlg.EditControl(LocalizedControlType="edit")
edit.GetValuePattern().SetValue(joined)
time.sleep(0.3)
btn = dlg.ButtonControl(Name="열기(O)")
if btn.Exists(1):
    btn.Click()
else:
    auto.SendKeys("{Enter}")
time.sleep(3.0)
play.screenshot(chrome, OUT / "after-library-upload.png")
print("TITLE", chrome.Name)
print("FILES", [p.name for p in files])
