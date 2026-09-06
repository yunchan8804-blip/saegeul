#!/usr/bin/env python3
# SPDX-License-Identifier: LGPL-2.1-or-later
# SPDX-FileCopyrightText: Copyright 2026 Yun Chan

"""Build the bundled base Korean vocabulary TSV used by the keyboard-aware
typo corrector and word completion.

Source is hermitdave/FrequencyWords ko_50k.txt (OpenSubtitles 2016), licensed
CC BY-SA 4.0. Filters out non-Hangul tokens, standalone-jamo-only tokens,
tokens outside the 1-12 syllable length range, and a short list of profanity
roots, then keeps the top 30,000 tokens by frequency.
"""

from __future__ import annotations

import argparse
import re
import urllib.request
from pathlib import Path

SOURCE_URL = (
    "https://raw.githubusercontent.com/hermitdave/FrequencyWords/master/"
    "content/2016/ko/ko_50k.txt"
)
SOURCE_ATTRIBUTION = (
    "# Source: hermitdave/FrequencyWords (OpenSubtitles 2016 ko_50k), "
    "License: CC BY-SA 4.0, https://github.com/hermitdave/FrequencyWords"
)
MAX_WORDS = 30_000
MIN_SYLLABLE_LENGTH = 1
MAX_SYLLABLE_LENGTH = 12

HANGUL_SYLLABLE = re.compile(r"[가-힣]")
TOKEN_ALLOWED = re.compile(r"^[가-힣ㄱ-ㆎ]+$")

PROFANITY_ROOTS = (
    "씨발", "시발", "씹", "병신", "개새", "좆", "지랄", "느금", "니미",
)


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--input",
        type=Path,
        help="Existing ko_50k.txt; downloads the pinned URL when omitted",
    )
    parser.add_argument(
        "--output",
        type=Path,
        default=Path(__file__).resolve().parents[1]
        / "app"
        / "src"
        / "main"
        / "assets"
        / "ko_base_vocab.tsv",
    )
    return parser.parse_args()


def acquire_lines(input_path: Path | None) -> list[str]:
    if input_path is not None:
        return input_path.read_text(encoding="utf-8").splitlines()
    with urllib.request.urlopen(SOURCE_URL) as response:
        return response.read().decode("utf-8").splitlines()


def is_valid_token(word: str) -> bool:
    if not (MIN_SYLLABLE_LENGTH <= len(word) <= MAX_SYLLABLE_LENGTH):
        return False
    if TOKEN_ALLOWED.fullmatch(word) is None:
        return False
    if HANGUL_SYLLABLE.search(word) is None:
        # Reject tokens made only of standalone compatibility jamo (e.g. "ㅋㅋㅋ").
        return False
    if any(root in word for root in PROFANITY_ROOTS):
        return False
    return True


def build_vocabulary(lines: list[str]) -> list[tuple[str, int]]:
    seen: dict[str, int] = {}
    for line in lines:
        parts = line.strip().split()
        if len(parts) < 2:
            continue
        word, freq_str = parts[0], parts[1]
        if not is_valid_token(word):
            continue
        try:
            freq = int(float(freq_str))
        except ValueError:
            continue
        if freq <= 0:
            continue
        if word in seen:
            seen[word] = max(seen[word], freq)
        else:
            seen[word] = freq
    ranked = sorted(seen.items(), key=lambda item: item[1], reverse=True)
    return ranked[:MAX_WORDS]


def write_vocabulary(entries: list[tuple[str, int]], output: Path) -> None:
    output.parent.mkdir(parents=True, exist_ok=True)
    with output.open("w", encoding="utf-8", newline="\n") as handle:
        handle.write(SOURCE_ATTRIBUTION + "\n")
        for word, freq in entries:
            handle.write(f"{word}\t{freq}\n")


def main() -> None:
    args = parse_args()
    lines = acquire_lines(args.input)
    entries = build_vocabulary(lines)
    write_vocabulary(entries, args.output)
    print(f"wrote {len(entries)} words to {args.output}")


if __name__ == "__main__":
    main()
