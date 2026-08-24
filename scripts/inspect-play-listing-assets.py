# SPDX-License-Identifier: LGPL-2.1-or-later
# SPDX-FileCopyrightText: Copyright 2026 Yun Chan
from pathlib import Path
from PIL import Image

root = Path(__file__).resolve().parents[1]
paths = list((root / "app/src/main/play/listings").rglob("*.png"))
paths += list((root / "artifacts/play-listing").rglob("*.png"))
for p in sorted(paths):
    im = Image.open(p)
    has_alpha = im.mode in ("RGBA", "LA", "PA") or ("transparency" in im.info)
    print(f"{p.relative_to(root)} {im.size} {im.mode} alpha={has_alpha}")
