# SPDX-License-Identifier: LGPL-2.1-or-later
# SPDX-FileCopyrightText: Copyright 2026 Yun Chan
"""Copy emulator captures into Play listing phone-screenshots as 24-bit PNG."""

from __future__ import annotations

from pathlib import Path

from PIL import Image

ROOT = Path(__file__).resolve().parents[1]
SRC = ROOT / "artifacts" / "play-listing"
LISTINGS = ROOT / "app" / "src" / "main" / "play" / "listings"

# Play Console: JPEG or 24-bit PNG, no alpha, 320–3840 px.
SHOTS = (
    ("emu-02-keyboard.png", "01-keyboard.png"),
    ("emu-01-main.png", "02-settings.png"),
    ("emu-06-input-methods.png", "03-input-methods.png"),
    ("emu-03-privacy.png", "04-privacy.png"),
    ("emu-04-about.png", "05-about.png"),
)


def to_rgb(src: Path) -> Image.Image:
    im = Image.open(src)
    if im.mode == "RGB":
        return im
    if im.mode == "RGBA":
        bg = Image.new("RGB", im.size, (255, 255, 255))
        bg.paste(im, mask=im.split()[-1])
        return bg
    return im.convert("RGB")


def main() -> None:
    locales = [LISTINGS / "ko-KR" / "graphics" / "phone-screenshots"]
    locales.append(LISTINGS / "en-US" / "graphics" / "phone-screenshots")
    artifact_dir = SRC / "phone-screenshots"
    artifact_dir.mkdir(parents=True, exist_ok=True)
    for dest_dir in locales:
        dest_dir.mkdir(parents=True, exist_ok=True)
        for old in dest_dir.glob("*.png"):
            old.unlink()
    for src_name, dest_name in SHOTS:
        src = SRC / src_name
        im = to_rgb(src)
        if not (320 <= min(im.size) and max(im.size) <= 3840):
            raise SystemExit(f"{src_name} size {im.size} is outside Play limits")
        im.save(artifact_dir / dest_name, "PNG")
        for dest_dir in locales:
            im.save(dest_dir / dest_name, "PNG")
        print(f"{dest_name} {im.size} {im.mode} from {src_name}")


if __name__ == "__main__":
    main()
