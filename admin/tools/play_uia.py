# SPDX-License-Identifier: LGPL-2.1-or-later
# SPDX-FileCopyrightText: Copyright 2026 Yun Chan
"""Shared Windows UI Automation helpers for the already-logged-in Play Console Chrome."""

from __future__ import annotations

import ctypes
import time
from pathlib import Path

import uiautomation as auto
from PIL import ImageGrab

DEVELOPER_ID = "7374613349497835192"
APP_ID = "4972063363550353487"
BASE = (
    "https://play.google.com/console/u/0/developers/"
    f"{DEVELOPER_ID}/app/{APP_ID}"
)

ALT = 0x12
SW_RESTORE = 9
SW_SHOW = 5
SW_MAXIMIZE = 3
user32 = ctypes.windll.user32


def find_saegeul_chrome():
    windows = []
    for w in auto.GetRootControl().GetChildren():
        if w.ClassName != "Chrome_WidgetWin_1":
            continue
        name = w.Name or ""
        if name in ("Bitwarden", "ZCode", "about:blank - Chrome"):
            continue
        windows.append(w)
    if not windows:
        raise RuntimeError("NO_USER_CHROME")
    for w in windows:
        n = w.Name or ""
        if "새글" in n or "Play Console" in n or "콘텐츠 등급" in n:
            return w
    for w in windows:
        if "Chrome" in (w.Name or ""):
            return w
    return windows[0]


def focus(chrome, maximize: bool = True) -> None:
    hwnd = chrome.NativeWindowHandle
    user32.ShowWindow(hwnd, SW_RESTORE)
    user32.ShowWindow(hwnd, SW_SHOW)
    user32.keybd_event(ALT, 0, 0, 0)
    user32.SetForegroundWindow(hwnd)
    user32.keybd_event(ALT, 0, 2, 0)
    if maximize:
        user32.ShowWindow(hwnd, SW_MAXIMIZE)
    chrome.SetActive()
    time.sleep(0.6)


def screenshot(chrome, path: Path) -> Path:
    path.parent.mkdir(parents=True, exist_ok=True)
    r = chrome.BoundingRectangle
    img = ImageGrab.grab(
        bbox=(max(r.left, 0), max(r.top, 0), r.right, r.bottom),
        all_screens=True,
    )
    img.save(path)
    return path


def set_omnibox(chrome, url: str) -> None:
    omnibox = chrome.EditControl(Name="주소창 및 검색창")
    if not omnibox.Exists(1):
        omnibox = chrome.EditControl(LocalizedControlType="edit")
    if not omnibox.Exists(0.5):
        auto.SendKeys("{Ctrl}l")
        time.sleep(0.2)
        auto.SendKeys(url, waitTime=0.02)
        auto.SendKeys("{Enter}")
        return
    omnibox.GetValuePattern().SetValue(url)
    omnibox.SendKeys("{Enter}")


def goto(chrome, url: str, wait: float = 4.0) -> None:
    set_omnibox(chrome, url)
    time.sleep(wait)


def walk(chrome, max_depth: int = 18):
    for c, d in auto.WalkControl(chrome, maxDepth=max_depth):
        yield c, d


def dump_interesting(chrome, keys: list[str] | None = None, max_depth: int = 18) -> None:
    keys = keys or []
    for c, d in walk(chrome, max_depth=max_depth):
        n = c.Name or ""
        if not n:
            continue
        if len(n) > 140:
            continue
        if keys and not any(k.lower() in n.lower() for k in keys):
            continue
        print(d, c.ControlTypeName, n)


def click_name(chrome, name: str, timeout: float = 3.0):
    ctrl = chrome.Control(searchDepth=18, Name=name)
    if not ctrl.Exists(timeout):
        return None
    ctrl.Click()
    time.sleep(0.8)
    return ctrl


def click_contains(chrome, needle: str, control_type: str | None = None, timeout: float = 2.0):
    deadline = time.time() + timeout
    while time.time() < deadline:
        for c, _d in walk(chrome, max_depth=20):
            n = c.Name or ""
            if needle not in n:
                continue
            if control_type and c.ControlTypeName != control_type:
                continue
            c.Click()
            time.sleep(0.8)
            return c
        time.sleep(0.2)
    return None


def set_edit_named(chrome, name: str, value: str) -> bool:
    edit = chrome.EditControl(searchDepth=20, Name=name)
    if not edit.Exists(1.5):
        return False
    try:
        edit.GetValuePattern().SetValue(value)
    except Exception:
        edit.Click()
        time.sleep(0.2)
        auto.SendKeys("{Ctrl}a")
        auto.SendKeys("{Delete}")
        auto.SendKeys(value, waitTime=0.01)
    time.sleep(0.3)
    return True


def paste_into_focused(text: str) -> None:
    auto.SetClipboardText(text)
    time.sleep(0.1)
    auto.SendKeys("{Ctrl}a")
    time.sleep(0.05)
    auto.SendKeys("{Ctrl}v")
    time.sleep(0.2)


def upload_via_file_dialog(file_path: Path, wait_dialog: float = 4.0) -> bool:
    path = str(file_path.resolve())
    dlg = auto.WindowControl(searchDepth=4, ClassName="#32770")
    if not dlg.Exists(wait_dialog):
        print("NO_FILE_DIALOG")
        return False
    print("FILE_DIALOG", dlg.Name, dlg.BoundingRectangle)
    edit = dlg.EditControl(Name="파일 이름(N):")
    if not edit.Exists(1):
        edit = dlg.EditControl(LocalizedControlType="edit")
    if not edit.Exists(1):
        print("NO_FILENAME_EDIT")
        return False
    edit.GetValuePattern().SetValue(path)
    time.sleep(0.2)
    btn = dlg.ButtonControl(Name="열기(O)")
    if not btn.Exists(1):
        btn = dlg.ButtonControl(Name="열기")
    if not btn.Exists(0.5):
        auto.SendKeys("{Enter}")
    else:
        btn.Click()
    time.sleep(1.5)
    return True
