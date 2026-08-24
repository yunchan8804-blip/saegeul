# SPDX-License-Identifier: LGPL-2.1-or-later
# SPDX-FileCopyrightText: Copyright 2026 Yun Chan
"""Print listing copy for Play Console paste."""

from pathlib import Path

root = Path(__file__).resolve().parents[1] / "app" / "src" / "main" / "play"
for locale in ("ko-KR", "en-US"):
    base = root / "listings" / locale
    print(f"===== {locale} title =====")
    print((base / "title.txt").read_text(encoding="utf-8").strip())
    print(f"===== {locale} short =====")
    print((base / "short-description.txt").read_text(encoding="utf-8").strip())
    print(f"===== {locale} full =====")
    print((base / "full-description.txt").read_text(encoding="utf-8").strip())
    print()
print("===== contact =====")
print((root / "contact-email.txt").read_text(encoding="utf-8").strip())
print((root / "contact-website.txt").read_text(encoding="utf-8").strip())
