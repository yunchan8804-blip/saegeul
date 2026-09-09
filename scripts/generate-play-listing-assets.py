# SPDX-License-Identifier: LGPL-2.1-or-later
# SPDX-FileCopyrightText: Copyright 2026 Yun Chan
"""Generate Google Play listing graphics from the Saegeul brand mark."""

from __future__ import annotations

from pathlib import Path

from PIL import Image, ImageDraw, ImageFilter, ImageFont

ROOT = Path(__file__).resolve().parents[1]
NAVY = (16, 24, 39, 255)
IVORY = (255, 249, 237, 255)
JADE = (85, 214, 166, 255)
JADE_BRIGHT = (121, 241, 194, 255)
INK = (10, 16, 28, 255)
MUTED = (148, 163, 184, 255)
WHITE = (255, 255, 255, 255)
KEY = (30, 41, 59, 255)
KEY_TOP = (51, 65, 85, 255)
SCREEN = (15, 23, 42, 255)

MALGUN = Path(r"C:\Windows\Fonts\malgun.ttf")
MALGUN_B = Path(r"C:\Windows\Fonts\malgunbd.ttf")
SEGOE = Path(r"C:\Windows\Fonts\segoeui.ttf")


def font(size: int, bold: bool = False) -> ImageFont.FreeTypeFont:
    path = MALGUN_B if bold and MALGUN_B.exists() else MALGUN
    if path.exists():
        return ImageFont.truetype(str(path), size)
    return ImageFont.truetype(str(SEGOE), size)


def draw_mark(draw: ImageDraw.ImageDraw, x: int, y: int, s: float) -> None:
    def r(px, py, w, h, fill):
        draw.rounded_rectangle(
            [x + px * s, y + py * s, x + (px + w) * s, y + (py + h) * s],
            radius=max(2, int(12 * s)),
            fill=fill,
        )

    r(302, 255, 304, 65, IVORY)
    r(415, 361, 63, 244, IVORY)
    # ㄱ-like foot
    r(302, 649, 40, 155, IVORY)
    r(302, 649, 290, 52, IVORY)
    r(672, 255, 63, 543, JADE)


def make_icon_512() -> Image.Image:
    img = Image.new("RGBA", (512, 512), (0, 0, 0, 0))
    draw = ImageDraw.Draw(img)
    draw.rounded_rectangle([0, 0, 511, 511], radius=96, fill=NAVY)
    # SVG is 1024 viewBox; scale to 512
    draw_mark(draw, 0, 0, 512 / 1024)
    return img


def make_feature_graphic() -> Image.Image:
    img = Image.new("RGB", (1024, 500), (16, 24, 39))
    draw = ImageDraw.Draw(img)
    # jade accent strip
    draw.rectangle([0, 0, 8, 500], fill=(85, 214, 166))
    # mark
    mark = Image.new("RGBA", (360, 360), (0, 0, 0, 0))
    md = ImageDraw.Draw(mark)
    draw_mark(md, 0, 0, 360 / 1024)
    img.paste(mark, (70, 70), mark)
    title = font(92, bold=True)
    sub = font(28)
    draw.text((470, 155), "새글", font=title, fill=(255, 249, 237))
    draw.text((470, 270), "한글이 먼저인 키보드", font=sub, fill=(85, 214, 166))
    draw.text((470, 318), "Saegeul  ·  independent Korean IME", font=font(22), fill=(148, 163, 184))
    return img


def rounded_rect(draw, box, radius, fill):
    draw.rounded_rectangle(box, radius=radius, fill=fill)


def status_bar(draw, w=1080):
    draw.text((48, 36), "9:41", font=font(28, bold=True), fill=WHITE)
    draw.text((900, 36), "5G  100%", font=font(24), fill=WHITE)


def draw_keyboard(draw, top: int) -> None:
    rows = [
        list("ㅂㅈㄷㄱㅅㅛㅕㅑㅐㅔ"),
        list("ㅁㄴㅇㄹㅎㅗㅓㅏㅣ"),
        ["⇧"] + list("ㅋㅌㅊㅍㅠㅜㅡ") + ["⌫"],
        ["!#1", "한/영", "공간", ".", "↵"],
    ]
    widths = [
        [96] * 10,
        [96] * 9,
        [110] + [90] * 7 + [110],
        [140, 160, 420, 140, 140],
    ]
    y = top
    for i, row in enumerate(rows):
        total = sum(widths[i]) + 12 * (len(row) - 1)
        x = (1080 - total) // 2
        for j, label in enumerate(row):
            ww = widths[i][j]
            fill = JADE[:3] if label == "↵" else KEY[:3]
            text_fill = INK[:3] if label == "↵" else IVORY[:3]
            rounded_rect(draw, [x, y, x + ww, y + 92], 18, fill)
            tw = draw.textlength(label, font=font(30, bold=True))
            draw.text((x + (ww - tw) / 2, y + 26), label, font=font(30, bold=True), fill=text_fill)
            x += ww + 12
        y += 108


def screenshot_keyboard() -> Image.Image:
    img = Image.new("RGB", (1080, 1920), (15, 23, 42))
    draw = ImageDraw.Draw(img)
    status_bar(draw)
    draw.text((72, 220), "메시지", font=font(28), fill=MUTED[:3])
    rounded_rect(draw, [72, 280, 1008, 520], 28, (30, 41, 59))
    draw.text((104, 330), "안녕하세요, 지금 새글로", font=font(40), fill=IVORY[:3])
    draw.text((104, 400), "한글을 조합하고 있어요|", font=font(40), fill=IVORY[:3])
    # candidate bar
    rounded_rect(draw, [0, 980, 1080, 1120], 0, (24, 32, 48))
    for i, cand in enumerate(["한글", "한굴", "한금", "한길"]):
        x = 48 + i * 250
        rounded_rect(draw, [x, 1010, x + 220, 1090], 16, KEY[:3])
        draw.text((x + 40, 1030), cand, font=font(32, bold=True), fill=IVORY[:3])
    draw_keyboard(draw, 1140)
    draw.text((72, 1840), "새글  ·  두벌식", font=font(22), fill=MUTED[:3])
    return img


def screenshot_setup() -> Image.Image:
    img = Image.new("RGB", (1080, 1920), (15, 23, 42))
    draw = ImageDraw.Draw(img)
    status_bar(draw)
    mark = Image.new("RGBA", (220, 220), (0, 0, 0, 0))
    draw_mark(ImageDraw.Draw(mark), 0, 0, 220 / 1024)
    img.paste(mark, (430, 220), mark)
    draw.text((360, 470), "새글", font=font(64, bold=True), fill=IVORY[:3])
    draw.text((250, 560), "한글이 먼저인 키보드", font=font(32), fill=JADE[:3])
    steps = [
        ("1", "새글을 입력 방법으로 사용"),
        ("2", "내장 한글 엔진으로 조합"),
        ("3", "기본 키보드로 선택"),
    ]
    y = 720
    for num, text in steps:
        rounded_rect(draw, [80, y, 1000, y + 140], 24, (30, 41, 59))
        rounded_rect(draw, [112, y + 36, 188, y + 104], 16, JADE[:3])
        draw.text((136, y + 50), num, font=font(32, bold=True), fill=INK[:3])
        draw.text((220, y + 48), text, font=font(34), fill=IVORY[:3])
        y += 168
    rounded_rect(draw, [80, 1680, 1000, 1820], 28, JADE[:3])
    draw.text((360, 1724), "설정 시작", font=font(36, bold=True), fill=INK[:3])
    return img


def screenshot_privacy() -> Image.Image:
    img = Image.new("RGB", (1080, 1920), (15, 23, 42))
    draw = ImageDraw.Draw(img)
    status_bar(draw)
    draw.text((72, 140), "←  개인정보·AI", font=font(36, bold=True), fill=IVORY[:3])
    cards = [
        ("오프라인 모드", "네트워크를 원클릭으로 차단합니다"),
        ("클립보드 기록", "기본값 꺼짐 · 백업에서 제외"),
        ("AI 글쓰기", "사용자가 켠 뒤에만 전송"),
        ("음성·GIF", "명시적 실행 없이는 보내지 않음"),
    ]
    y = 260
    for title, body in cards:
        rounded_rect(draw, [72, y, 1008, y + 210], 28, (30, 41, 59))
        draw.text((112, y + 40), title, font=font(36, bold=True), fill=IVORY[:3])
        draw.text((112, y + 110), body, font=font(28), fill=MUTED[:3])
        y += 236
    draw.text((72, 1800), "saegul.chanpaca.net/privacy/", font=font(24), fill=JADE[:3])
    return img


def screenshot_about() -> Image.Image:
    img = Image.new("RGB", (1080, 1920), (15, 23, 42))
    draw = ImageDraw.Draw(img)
    status_bar(draw)
    mark = Image.new("RGBA", (180, 180), (0, 0, 0, 0))
    draw_mark(ImageDraw.Draw(mark), 0, 0, 180 / 1024)
    img.paste(mark, (450, 200), mark)
    draw.text((390, 420), "새글", font=font(56, bold=True), fill=IVORY[:3])
    draw.text((330, 500), "0.1.0-rc.13", font=font(28), fill=MUTED[:3])
    rounded_rect(draw, [72, 620, 1008, 980], 28, (30, 41, 59))
    notice = (
        "새글은 Fcitx5를 기반으로 한\n"
        "비공식 독립 포크이며,\n"
        "원 프로젝트와 제휴하거나\n"
        "보증을 받지 않습니다."
    )
    draw.multiline_text((112, 670), notice, font=font(32), fill=IVORY[:3], spacing=12)
    rows = [
        "개인정보처리방침",
        "소스 코드",
        "라이선스",
        "FAQ",
    ]
    y = 1040
    for row in rows:
        rounded_rect(draw, [72, y, 1008, y + 120], 22, (30, 41, 59))
        draw.text((112, y + 36), row, font=font(32), fill=IVORY[:3])
        draw.text((930, y + 36), "→", font=font(32), fill=JADE[:3])
        y += 140
    return img


def save_png(img: Image.Image, path: Path, *, rgba: bool = False) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    if rgba:
        img.convert("RGBA").save(path, "PNG")
    else:
        img.convert("RGB").save(path, "PNG")
    print("wrote", path, img.size)


def main() -> None:
    icon = make_icon_512()
    feature = make_feature_graphic()
    shots = [
        ("01-setup.png", screenshot_setup()),
        ("02-keyboard.png", screenshot_keyboard()),
        ("03-privacy.png", screenshot_privacy()),
        ("04-about.png", screenshot_about()),
    ]
    dests = [
        ROOT / "app/src/main/play/listings/ko-KR/graphics",
        ROOT / "app/src/main/play/listings/en-US/graphics",
        ROOT / "artifacts/play-listing",
    ]
    for dest in dests:
        save_png(icon, dest / "icon/icon.png", rgba=True)
        save_png(feature, dest / "feature-graphic/feature-graphic.png", rgba=False)
        for name, shot in shots:
            save_png(shot, dest / "phone-screenshots" / name, rgba=False)


if __name__ == "__main__":
    main()
