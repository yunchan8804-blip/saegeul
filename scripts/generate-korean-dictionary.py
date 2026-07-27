#!/usr/bin/env python3
"""Generate the bundled Korean dictionary from a pinned Wiktextract snapshot.

The output is a deterministic gzip-compressed TSV. It contains only Korean-language
entries whose headword is written in modern Hangul, and up to four normalized glosses
for each headword/part-of-speech pair.
"""

from __future__ import annotations

import argparse
import gzip
import hashlib
import io
import json
import re
import shutil
import tempfile
import urllib.request
from collections import OrderedDict
from pathlib import Path


SOURCE_URL = "https://kaikki.org/kowiktionary/raw-wiktextract-data.jsonl.gz"
SOURCE_SHA256 = "DF65C8B26BD20DED6D7FC7616106670443C08B551E8D61949A1040E6D68A22E1"
SOURCE_DUMP_DATE = "2026-07-03"
SOURCE_EXTRACT_DATE = "2026-07-24"
SOURCE_WIKTEXTRACT_COMMITS = "d9fa233, 9e92f4b"
HEADWORD = re.compile(r"^[가-힣]+(?:[- ][가-힣]+)*$")
MAX_HEADWORD_LENGTH = 40
MAX_GLOSSES = 4


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser()
    parser.add_argument(
        "--source",
        type=Path,
        help="Existing raw-wiktextract-data.jsonl.gz; downloads the pinned URL when omitted",
    )
    parser.add_argument(
        "--output",
        type=Path,
        default=Path(__file__).resolve().parents[1]
        / "app"
        / "src"
        / "main"
        / "assets"
        / "korean"
        / "dictionary.tsv.gzip",
    )
    return parser.parse_args()


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as source:
        for chunk in iter(lambda: source.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest().upper()


def acquire_source(source: Path | None) -> tuple[Path, bool]:
    if source is not None:
        return source.resolve(), False
    handle = tempfile.NamedTemporaryFile(prefix="kowiktionary-", suffix=".jsonl.gz", delete=False)
    handle.close()
    target = Path(handle.name)
    try:
        with urllib.request.urlopen(SOURCE_URL) as response, target.open("wb") as output:
            shutil.copyfileobj(response, output)
    except Exception:
        target.unlink(missing_ok=True)
        raise
    return target, True


def clean_text(value: str) -> str:
    return " ".join(value.replace("\t", " ").split())


def extract(source: Path) -> OrderedDict[tuple[str, str], list[str]]:
    entries: OrderedDict[tuple[str, str], list[str]] = OrderedDict()
    with gzip.open(source, "rt", encoding="utf-8") as rows:
        for row in rows:
            record = json.loads(row)
            if record.get("lang_code") != "ko":
                continue
            word = clean_text(record.get("word") or "")
            if (
                not word
                or len(word) > MAX_HEADWORD_LENGTH
                or HEADWORD.fullmatch(word) is None
            ):
                continue
            pos = clean_text(record.get("pos_title") or record.get("pos") or "")
            glosses: list[str] = []
            for sense in record.get("senses") or []:
                for value in sense.get("glosses") or []:
                    gloss = clean_text(value)
                    if gloss and gloss not in glosses:
                        glosses.append(gloss)
            if not glosses:
                continue
            current = entries.setdefault((word, pos), [])
            for gloss in glosses:
                if gloss not in current and len(current) < MAX_GLOSSES:
                    current.append(gloss)
    return OrderedDict(sorted(entries.items(), key=lambda item: item[0]))


def write_output(output: Path, entries: OrderedDict[tuple[str, str], list[str]]) -> None:
    output.parent.mkdir(parents=True, exist_ok=True)
    headers = (
        "# Korean Wiktionary offline dictionary extract",
        f"# Source: {SOURCE_URL}",
        f"# Source SHA-256: {SOURCE_SHA256}",
        f"# Korean Wiktionary dump: {SOURCE_DUMP_DATE}",
        f"# Wiktextract build: {SOURCE_EXTRACT_DATE} ({SOURCE_WIKTEXTRACT_COMMITS})",
        "# License: CC BY-SA 4.0; attribution: Korean Wiktionary contributors",
        "# Modified: Korean entries and modern-Hangul headwords only; whitespace normalized; four glosses maximum",
        "# Format: headword<TAB>part of speech<TAB>definition...",
    )
    with output.open("wb") as raw:
        with gzip.GzipFile(fileobj=raw, mode="wb", compresslevel=9, mtime=0) as compressed:
            with io.TextIOWrapper(compressed, encoding="utf-8", newline="\n") as text:
                for header in headers:
                    text.write(header + "\n")
                for (word, pos), glosses in entries.items():
                    text.write("\t".join((word, pos, *glosses)) + "\n")


def main() -> None:
    args = parse_args()
    source, temporary = acquire_source(args.source)
    try:
        actual = sha256(source)
        if actual != SOURCE_SHA256:
            raise SystemExit(
                f"Unexpected source SHA-256: {actual}; expected pinned {SOURCE_SHA256}"
            )
        entries = extract(source)
        write_output(args.output.resolve(), entries)
        words = len({word for word, _ in entries})
        print(
            f"Wrote {len(entries)} entries / {words} headwords to {args.output.resolve()} "
            f"(SHA-256 {sha256(args.output.resolve())})"
        )
    finally:
        if temporary:
            source.unlink(missing_ok=True)


if __name__ == "__main__":
    main()
