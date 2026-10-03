#!/usr/bin/env python3
# Source: HuggingFaceFW/fineweb-2 (config kor_Hang, test split), License: ODC-By 1.0,
# https://huggingface.co/datasets/HuggingFaceFW/fineweb-2

"""Build the held-out sentence set for the next-word baseline benchmark.

The bundled n-gram (scripts/build-ko-corpus-ngram.py) is built from the kor_Hang
train shard only. This script samples sentences from the kor_Hang test split so
the benchmark never sees text the bundled model was built from.

Output: app/src/test/resources/benchmark/ko-heldout-fineweb2-test.txt
Raw parquet is cached under build/next-word-heldout/.
"""

import argparse
import random
import re
import sys
from datetime import date
from pathlib import Path

import pyarrow.parquet as pq
from huggingface_hub import hf_hub_download

REPO_ID = "HuggingFaceFW/fineweb-2"
TEST_FILE = "data/kor_Hang/test/000_00000.parquet"
SEED = 20261002
SAMPLE_SIZE = 3000
MIN_WORDS = 4
MAX_WORDS = 25
MIN_HANGUL_RATIO = 0.6

REPO_ROOT = Path(__file__).resolve().parent.parent
RAW_DIR = REPO_ROOT / "build" / "next-word-heldout"
OUTPUT = REPO_ROOT / "app" / "src" / "test" / "resources" / "benchmark" / "ko-heldout-fineweb2-test.txt"

SENTENCE_SPLIT = re.compile(r"[.?!\n]+")
WHITESPACE = re.compile(r"\s+")
URL = re.compile(r"(https?://|www\.)", re.IGNORECASE)
EMAIL = re.compile(r"\S+@\S+\.\S+")
DIGITS_ONLY = re.compile(r"^[\d,.\-:/%]+$")


def is_hangul_syllable(ch: str) -> bool:
    return "가" <= ch <= "힣"


def accept(sentence: str) -> bool:
    words = sentence.split(" ")
    if not (MIN_WORDS <= len(words) <= MAX_WORDS):
        return False
    if URL.search(sentence) or EMAIL.search(sentence):
        return False
    letters = [ch for ch in sentence if not ch.isspace()]
    if not letters:
        return False
    if sum(is_hangul_syllable(ch) for ch in letters) / len(letters) < MIN_HANGUL_RATIO:
        return False
    numeric = sum(1 for word in words if DIGITS_ONLY.match(word))
    return numeric * 2 <= len(words)


def iter_sentences(parquet_path: Path):
    reader = pq.ParquetFile(parquet_path)
    for batch in reader.iter_batches(batch_size=512, columns=["text"]):
        for text in batch.column("text").to_pylist():
            for raw in SENTENCE_SPLIT.split(text):
                sentence = WHITESPACE.sub(" ", raw).strip()
                if sentence and accept(sentence):
                    yield sentence


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=Path, default=OUTPUT)
    args = parser.parse_args()

    RAW_DIR.mkdir(parents=True, exist_ok=True)
    local = hf_hub_download(REPO_ID, TEST_FILE, repo_type="dataset", local_dir=RAW_DIR)
    print(f"parquet={local}", file=sys.stderr)

    seen: set[str] = set()
    pool: list[str] = []
    for sentence in iter_sentences(Path(local)):
        if sentence in seen:
            continue
        seen.add(sentence)
        pool.append(sentence)
    print(f"accepted unique sentences={len(pool)}", file=sys.stderr)
    if len(pool) < SAMPLE_SIZE:
        raise SystemExit(f"pool {len(pool)} is smaller than sample size {SAMPLE_SIZE}")

    sample = random.Random(SEED).sample(pool, SAMPLE_SIZE)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    header = (
        f"# source=HuggingFaceFW/fineweb-2 config=kor_Hang split=test file={TEST_FILE} "
        f"license=ODC-By 1.0 seed={SEED} sample={SAMPLE_SIZE} generated={date.today().isoformat()}"
    )
    args.output.write_text(header + "\n" + "\n".join(sample) + "\n", encoding="utf-8", newline="\n")
    print(f"wrote {args.output} ({len(sample)} sentences)", file=sys.stderr)


if __name__ == "__main__":
    main()
