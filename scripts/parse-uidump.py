# SPDX-License-Identifier: LGPL-2.1-or-later
# SPDX-FileCopyrightText: Copyright 2026 Yun Chan
import sys
import xml.etree.ElementTree as ET

path = sys.argv[1]
root = ET.parse(path).getroot()
for n in root.iter("node"):
    text = n.get("text") or ""
    desc = n.get("content-desc") or ""
    cls = (n.get("class") or "").split(".")[-1]
    rid = (n.get("resource-id") or "").split("/")[-1]
    bounds = n.get("bounds")
    clickable = n.get("clickable")
    focusable = n.get("focusable")
    blob = (text + desc + rid + cls).lower()
    interesting = any(
        x in blob
        for x in ("edit", "message", "compose", "input", "text", "ime", "keyboard")
    )
    if interesting or clickable == "true":
        print(
            f"{cls:20} click={clickable} foc={focusable} bounds={bounds} "
            f"id={rid!r} text={text[:80]!r} desc={desc[:80]!r}"
        )
