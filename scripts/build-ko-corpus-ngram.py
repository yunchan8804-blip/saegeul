#!/usr/bin/env python3
# Source: HuggingFaceFW/fineweb-2 (config kor_Hang), License: ODC-By 1.0,
# https://huggingface.co/datasets/HuggingFaceFW/fineweb-2

"""Build candidate Korean word-level n-gram packs from FineWeb-2 (kor_Hang)
parquet shards, for offline comparison against the currently bundled
ko_base_vocab.tsv / nextword.txt. This is an experiment-only script; it does
not write anything under app/ or plugin/.

Tokenization: split text into sentences on '.', '?', '!', '...'/'…' runs and
on newlines. Within a sentence, split on whitespace; for each token, strip
only leading/trailing Unicode punctuation (category P*) and symbol (category
S*) characters. The remainder is a valid eojeol only if it fully matches
^[가-힣]{1,12}$ (i.e. contains nothing but Hangul syllables) and none of the
profanity roots (reused from build-ko-base-vocab.py). Anything with a
non-Hangul character left after stripping punctuation/symbols (digits,
Latin letters, Hanja, standalone jamo, mixed tokens like "2023년" or
"Apple의") is invalid. A rejected token breaks the n-gram chain (does not
get skipped-and-joined); bigrams/trigrams only span consecutive valid
tokens within the same sentence.

Sentence-level exact dedup: sentences with >= 2 valid eojeols are matched by
a normalized-text hash (whitespace collapsed, blake2b digest_size=8) against
every sentence seen so far in the run; a repeat is not counted into any
unigram/bigram/trigram or into valid_words_seen (boilerplate such as site
footers or repeated notices would otherwise inflate frequencies). Sentences
with fewer than 2 valid eojeols are always counted and never hashed.

Colloquial-register weighting: two weighted variants are accumulated in the
same pass over each counted sentence (see NgramCounter, count[0]/count[1]):
  - w1: raw occurrence count, no weighting.
  - w2: every n-gram token occurrence in a sentence gets weight
    COLLOQUIAL_WEIGHT (default 3) instead of 1 if that sentence's last valid
    eojeol does not end in '다' and does end in one of COLLOQUIAL_ENDINGS.
doc_freq (distinct source documents) is never weighted, for either variant.

Privacy/noise guard: unigram/bigram/trigram entries are only kept in the
final pack if they occur in at least MIN_DOC_FREQ distinct source documents
(default 3), in addition to a raw min-count threshold on the chosen weight
variant.

Memory control: exact counts are kept for unigrams (their cardinality stays
small enough). Bigram/trigram running dictionaries are periodically pruned
of hapax-legomena entries (raw occurrence count == 1, i.e. seen in exactly
one document exactly once, judged on the unweighted count so the w2 weight
never hides a true hapax) once they cross a size guard, to keep peak memory
bounded on a single pass over multi-GB shards. The size guard is only
checked every `--prune-check-interval-docs` documents (not after every
document): checking on every document made the prune fire on nearly every
document once the dictionary of *recurring* (count > 1) grams alone
approached the cap, because each new document still adds a handful of fresh
singleton grams that push len() back over the threshold — that turned every
prune into an O(n) dictionary rebuild done once per document, i.e.
catastrophic thrashing with near-zero forward progress. Checking only
periodically amortizes the rebuild cost across many documents instead. This
periodic pruning is still an approximation: a bigram/trigram pruned as a
singleton and then reoccurring long after the prune point restarts from
count=1 instead of accumulating past occurrences, which can undercount
grams that are rare-but-real and spread far apart in the shard. Because the
final filter already requires doc-frequency >= 3, this mainly affects
entries near that boundary; it is documented here rather than hidden.
"""

from __future__ import annotations

import argparse
import gzip
import hashlib
import importlib.util
import json
import random
import re
import sys
import time
import unicodedata
from pathlib import Path
from typing import Iterator

import pyarrow.parquet as pq

sys.path.insert(0, str(Path(__file__).resolve().parent))
from ko_doc_quality import assess as assess_document  # noqa: E402

try:
    import psutil
except ImportError:  # pragma: no cover - psutil is installed in the scratchpad venv
    psutil = None

SCRIPT_DIR = Path(__file__).resolve().parent


def _load_profanity_roots() -> tuple[str, ...]:
    """Reuse PROFANITY_ROOTS from scripts/build-ko-base-vocab.py by loading
    that module directly (no code duplication)."""
    candidates = [
        SCRIPT_DIR / "build-ko-base-vocab.py",
        Path("D:/workspace/Saegul/scripts/build-ko-base-vocab.py"),
    ]
    for path in candidates:
        if path.is_file():
            spec = importlib.util.spec_from_file_location("build_ko_base_vocab", path)
            module = importlib.util.module_from_spec(spec)
            assert spec.loader is not None
            spec.loader.exec_module(module)
            return tuple(module.PROFANITY_ROOTS)
    raise FileNotFoundError("could not locate scripts/build-ko-base-vocab.py to reuse PROFANITY_ROOTS")


PROFANITY_ROOTS = _load_profanity_roots()

VALID_WORD = re.compile(r"^[가-힣]{1,12}$")
SENTENCE_SPLIT = re.compile(r"[\r\n]+|[.?!…]+")
WHITESPACE_RUN = re.compile(r"\s+")

COLLOQUIAL_ENDINGS = ("요", "어", "아", "야", "지", "해", "네", "게", "자", "까", "냐", "래", "데", "걸", "죠", "니")
COLLOQUIAL_WEIGHT = 3


def _is_punct_or_symbol(ch: str) -> bool:
    return unicodedata.category(ch)[0] in ("P", "S")


def clean_token(token: str) -> str | None:
    start, end = 0, len(token)
    while start < end and _is_punct_or_symbol(token[start]):
        start += 1
    while end > start and _is_punct_or_symbol(token[end - 1]):
        end -= 1
    stripped = token[start:end]
    if not stripped:
        return None
    if VALID_WORD.fullmatch(stripped) is None:
        return None
    if any(root in stripped for root in PROFANITY_ROOTS):
        return None
    return stripped


def _runs_for_sentence(sentence: str) -> list[list[str]]:
    runs: list[list[str]] = []
    current: list[str] = []
    for raw in sentence.split():
        word = clean_token(raw)
        if word is None:
            if current:
                runs.append(current)
                current = []
            continue
        current.append(word)
    if current:
        runs.append(current)
    return runs


def iter_sentences(text: str) -> Iterator[tuple[str, list[list[str]]]]:
    """Yield (raw_sentence_text, runs) for each non-empty sentence in text,
    where `runs` are the maximal runs of consecutive valid Hangul eojeol
    tokens within that sentence (invalid tokens break a run)."""
    for sentence in SENTENCE_SPLIT.split(text):
        if not sentence:
            continue
        yield sentence, _runs_for_sentence(sentence)


def sentence_runs(text: str) -> list[list[str]]:
    """Flat list of valid-eojeol runs across all sentences in text. Used by
    eval-ko-base-pack.py, which does not need sentence-level dedup or
    colloquial weighting."""
    runs: list[list[str]] = []
    for _sentence, sentence_run_list in iter_sentences(text):
        runs.extend(sentence_run_list)
    return runs


def normalize_sentence(sentence: str) -> str:
    return WHITESPACE_RUN.sub(" ", sentence).strip()


def is_colloquial_ending(word: str) -> bool:
    return not word.endswith("다") and word.endswith(COLLOQUIAL_ENDINGS)


class NgramCounter:
    """Streaming unigram/bigram/trigram counter. Each stats dict maps gram
    -> [count_w1, count_w2, doc_freq]: count_w1 is the unweighted raw
    occurrence count, count_w2 is the colloquial-weighted count (weight
    COLLOQUIAL_WEIGHT on every n-gram token occurrence drawn from a sentence
    whose last valid eojeol has a colloquial, non-'다' ending), and
    doc_freq is the number of distinct source documents the gram appeared
    in (never weighted). Sentence-level exact dedup and periodic
    hapax-legomena pruning for bigram/trigram (see module docstring) are
    both handled here."""

    def __init__(self, max_bi_entries: int, max_tri_entries: int, prune_check_interval_docs: int = 5000) -> None:
        self.uni: dict[str, list[int]] = {}
        self.bi: dict[tuple[str, str], list[int]] = {}
        self.tri: dict[tuple[str, str, str], list[int]] = {}
        self.max_bi_entries = max_bi_entries
        self.max_tri_entries = max_tri_entries
        self.prune_check_interval_docs = prune_check_interval_docs
        self.docs_seen = 0
        self.valid_words_seen = 0
        self.bi_prune_events = 0
        self.tri_prune_events = 0
        self.bi_pruned_total = 0
        self.tri_pruned_total = 0

        self.seen_sentence_hashes: set[bytes] = set()
        self.sentences_total = 0
        self.sentences_short = 0
        self.sentences_duplicate = 0
        self.sentences_counted = 0
        self.colloquial_sentences = 0

    def add_document(self, text: str) -> None:
        self.docs_seen += 1
        doc_uni: set[str] = set()
        doc_bi: set[tuple[str, str]] = set()
        doc_tri: set[tuple[str, str, str]] = set()

        for sentence, runs in iter_sentences(text):
            self.sentences_total += 1
            total_valid = sum(len(r) for r in runs)
            if not runs:
                continue
            if total_valid >= 2:
                norm = normalize_sentence(sentence)
                h = hashlib.blake2b(norm.encode("utf-8"), digest_size=8).digest()
                if h in self.seen_sentence_hashes:
                    self.sentences_duplicate += 1
                    continue
                self.seen_sentence_hashes.add(h)
            else:
                self.sentences_short += 1

            self.sentences_counted += 1
            last_word = runs[-1][-1]
            colloquial = is_colloquial_ending(last_word)
            if colloquial:
                self.colloquial_sentences += 1
            weight = COLLOQUIAL_WEIGHT if colloquial else 1

            for run in runs:
                self.valid_words_seen += len(run)
                for i, w in enumerate(run):
                    entry = self.uni.setdefault(w, [0, 0, 0])
                    entry[0] += 1
                    entry[1] += weight
                    doc_uni.add(w)
                    if i + 1 < len(run):
                        bg = (w, run[i + 1])
                        entry_b = self.bi.setdefault(bg, [0, 0, 0])
                        entry_b[0] += 1
                        entry_b[1] += weight
                        doc_bi.add(bg)
                    if i + 2 < len(run):
                        tg = (w, run[i + 1], run[i + 2])
                        entry_t = self.tri.setdefault(tg, [0, 0, 0])
                        entry_t[0] += 1
                        entry_t[1] += weight
                        doc_tri.add(tg)

        for w in doc_uni:
            self.uni[w][2] += 1
        for bg in doc_bi:
            self.bi[bg][2] += 1
        for tg in doc_tri:
            self.tri[tg][2] += 1

        if self.docs_seen % self.prune_check_interval_docs == 0:
            self._maybe_prune()

    def _maybe_prune(self) -> None:
        if len(self.bi) > self.max_bi_entries:
            before = len(self.bi)
            self.bi = {g: v for g, v in self.bi.items() if v[0] > 1}
            self.bi_pruned_total += before - len(self.bi)
            self.bi_prune_events += 1
        if len(self.tri) > self.max_tri_entries:
            before = len(self.tri)
            self.tri = {g: v for g, v in self.tri.items() if v[0] > 1}
            self.tri_pruned_total += before - len(self.tri)
            self.tri_prune_events += 1


def rss_mb() -> float | None:
    if psutil is None:
        return None
    return psutil.Process().memory_info().rss / (1024 * 1024)


def iter_shard_texts(shard_path: Path, columns=("text",)):
    pf = pq.ParquetFile(shard_path)
    for batch in pf.iter_batches(batch_size=4096, columns=list(columns)):
        for text in batch.column("text").to_pylist():
            yield text


def sha256_of(path: Path) -> str:
    h = hashlib.sha256()
    with path.open("rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def write_tsv_gz_sizes(path: Path, lines: list[str]) -> tuple[int, int]:
    path.parent.mkdir(parents=True, exist_ok=True)
    data = ("\n".join(lines) + "\n").encode("utf-8")
    path.write_bytes(data)
    gz_size = len(gzip.compress(data, compresslevel=9))
    return len(data), gz_size


def build_unigram_pack(counter: NgramCounter, min_doc_freq: int, topn: int, count_index: int):
    filtered = [
        (w, c[count_index], c[2])
        for w, c in counter.uni.items()
        if c[2] >= min_doc_freq
    ]
    filtered.sort(key=lambda t: t[1], reverse=True)
    return filtered[:topn], filtered


def build_bigram_pack(
    counter: NgramCounter, vocab: set[str], min_doc_freq: int, min_count: int, topk: int, count_index: int
):
    grouped: dict[str, list[tuple[str, int, int]]] = {}
    for (w1, w2), c in counter.bi.items():
        count, docfreq = c[count_index], c[2]
        if docfreq < min_doc_freq or count < min_count:
            continue
        if w1 not in vocab or w2 not in vocab:
            continue
        grouped.setdefault(w1, []).append((w2, count, docfreq))
    rows: list[tuple[str, str, int]] = []
    for w1, cands in grouped.items():
        cands.sort(key=lambda t: t[1], reverse=True)
        for w2, count, _docfreq in cands[:topk]:
            rows.append((w1, w2, count))
    rows.sort(key=lambda t: t[2], reverse=True)
    return rows


def build_trigram_pack(
    counter: NgramCounter,
    vocab: set[str],
    min_doc_freq: int,
    min_count: int,
    topk: int,
    cap: int,
    count_index: int,
):
    grouped: dict[tuple[str, str], list[tuple[str, int, int]]] = {}
    for (w1, w2, w3), c in counter.tri.items():
        count, docfreq = c[count_index], c[2]
        if docfreq < min_doc_freq or count < min_count:
            continue
        if w1 not in vocab or w2 not in vocab or w3 not in vocab:
            continue
        grouped.setdefault((w1, w2), []).append((w3, count, docfreq))
    rows: list[tuple[str, str, str, int]] = []
    for (w1, w2), cands in grouped.items():
        cands.sort(key=lambda t: t[1], reverse=True)
        for w3, count, _docfreq in cands[:topk]:
            rows.append((w1, w2, w3, count))
    rows.sort(key=lambda t: t[3], reverse=True)
    return rows[:cap], rows


def write_pack(
    counter: NgramCounter,
    out_dir: Path,
    count_index: int,
    min_doc_freq: int,
    uni_topn: int,
    bigram_topk: int,
    bigram_min_count: int,
    trigram_topk: int,
    trigram_min_count: int,
    trigram_cap: int,
    sample_seed: int,
) -> dict:
    out_dir.mkdir(parents=True, exist_ok=True)
    file_sizes: dict[str, dict[str, int]] = {}

    uni_top, uni_filtered = build_unigram_pack(counter, min_doc_freq, uni_topn, count_index)
    lines = [f"{w}\t{c}\t{d}" for w, c, d in uni_top]
    path = out_dir / "unigram.tsv"
    raw, gz = write_tsv_gz_sizes(path, lines)
    file_sizes[path.name] = {"raw_bytes": raw, "gzip_bytes": gz, "rows": len(uni_top)}

    vocab = {w for w, _c, _d in uni_top}
    bigram_rows = build_bigram_pack(counter, vocab, min_doc_freq, bigram_min_count, bigram_topk, count_index)
    lines = [f"{w1}\t{w2}\t{c}" for w1, w2, c in bigram_rows]
    path = out_dir / "bigram.tsv"
    raw, gz = write_tsv_gz_sizes(path, lines)
    file_sizes[path.name] = {"raw_bytes": raw, "gzip_bytes": gz, "rows": len(bigram_rows)}

    trigram_top, trigram_all = build_trigram_pack(
        counter, vocab, min_doc_freq, trigram_min_count, trigram_topk, trigram_cap, count_index
    )
    lines = [f"{w1}\t{w2}\t{w3}\t{c}" for w1, w2, w3, c in trigram_top]
    path = out_dir / "trigram.tsv"
    raw, gz = write_tsv_gz_sizes(path, lines)
    file_sizes[path.name] = {"raw_bytes": raw, "gzip_bytes": gz, "rows": len(trigram_top)}

    rng = random.Random(sample_seed)
    sample_uni_top50 = [w for w, _c, _d in uni_filtered[:50]]
    sample_bi_random30 = rng.sample(bigram_rows, min(30, len(bigram_rows))) if bigram_rows else []
    sample_tri_random20 = rng.sample(trigram_all, min(20, len(trigram_all))) if trigram_all else []
    sample_bi_top30 = bigram_rows[:30]
    sample_tri_top30 = trigram_top[:30]

    return {
        "vocab_size": len(vocab),
        "unigram_after_docfreq_filter": len(uni_filtered),
        "file_sizes": file_sizes,
        "sample_unigram_top50": sample_uni_top50,
        "sample_bigram_random30": [{"prev": w1, "next": w2, "count": c} for w1, w2, c in sample_bi_random30],
        "sample_bigram_top30": [{"prev": w1, "next": w2, "count": c} for w1, w2, c in sample_bi_top30],
        "sample_trigram_random20": [
            {"w1": w1, "w2": w2, "next": w3, "count": c} for w1, w2, w3, c in sample_tri_random20
        ],
        "sample_trigram_top30": [{"w1": w1, "w2": w2, "next": w3, "count": c} for w1, w2, w3, c in sample_tri_top30],
    }


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--shard", type=Path, required=True, help="local FineWeb-2 kor_Hang train parquet shard")
    parser.add_argument("--out-dir", type=Path, required=True, help="parent dir; pack-w1/ and pack-w2/ are created under it")
    parser.add_argument("--target-words", type=int, default=200_000_000, help="deduped valid-word target (post exact-dup removal)")
    parser.add_argument("--max-docs", type=int, default=0, help="0 = no cap besides target-words / shard length")
    parser.add_argument("--min-doc-freq", type=int, default=3)
    parser.add_argument("--uni-topn", type=int, default=150_000)
    parser.add_argument("--bigram-topk", type=int, default=8)
    parser.add_argument("--bigram-min-count", type=int, default=5)
    parser.add_argument("--trigram-topk", type=int, default=5)
    parser.add_argument("--trigram-min-count", type=int, default=3)
    parser.add_argument("--trigram-cap", type=int, default=200_000)
    parser.add_argument("--max-bi-entries", type=int, default=4_500_000)
    parser.add_argument("--max-tri-entries", type=int, default=6_000_000)
    parser.add_argument("--prune-check-interval-docs", type=int, default=5000)
    parser.add_argument("--sample-seed", type=int, default=20260924)
    parser.add_argument(
        "--no-doc-filter",
        action="store_true",
        help="count every document (skip the spam/contact/commercial/repetitive gate in ko_doc_quality.py)",
    )
    args = parser.parse_args()

    t0 = time.time()
    shard_sha256 = sha256_of(args.shard)
    print(f"shard={args.shard} sha256={shard_sha256}", file=sys.stderr)

    counter = NgramCounter(args.max_bi_entries, args.max_tri_entries, args.prune_check_interval_docs)
    last_report = time.time()
    doc_filter_counts: dict[str, int] = {}
    for text in iter_shard_texts(args.shard):
        if not args.no_doc_filter:
            verdict = assess_document(text)
            doc_filter_counts[verdict.reason] = doc_filter_counts.get(verdict.reason, 0) + 1
            if not verdict.keep:
                continue
        counter.add_document(text)
        if counter.valid_words_seen >= args.target_words:
            break
        if args.max_docs and counter.docs_seen >= args.max_docs:
            break
        if time.time() - last_report > 15:
            mem = rss_mb()
            print(
                f"docs={counter.docs_seen} valid_words={counter.valid_words_seen} "
                f"sent_total={counter.sentences_total} sent_dup={counter.sentences_duplicate} "
                f"sent_colloquial={counter.colloquial_sentences} "
                f"uni={len(counter.uni)} bi={len(counter.bi)} tri={len(counter.tri)} "
                f"bi_prunes={counter.bi_prune_events} tri_prunes={counter.tri_prune_events} "
                f"rss_mb={mem} elapsed_s={time.time() - t0:.1f}",
                file=sys.stderr,
            )
            last_report = time.time()

    counting_elapsed = time.time() - t0
    peak_rss = rss_mb()
    dup_ratio = counter.sentences_duplicate / counter.sentences_total if counter.sentences_total else 0.0
    colloquial_ratio = counter.colloquial_sentences / counter.sentences_counted if counter.sentences_counted else 0.0
    print(
        f"DONE docs={counter.docs_seen} valid_words={counter.valid_words_seen} "
        f"sent_total={counter.sentences_total} sent_dup={counter.sentences_duplicate} "
        f"dup_ratio={dup_ratio:.4f} sent_colloquial={counter.colloquial_sentences} "
        f"colloquial_ratio={colloquial_ratio:.4f} "
        f"uni_unique={len(counter.uni)} bi_unique={len(counter.bi)} tri_unique={len(counter.tri)} "
        f"elapsed_s={counting_elapsed:.1f} rss_mb={peak_rss}",
        file=sys.stderr,
    )

    args.out_dir.mkdir(parents=True, exist_ok=True)

    pack_kwargs = dict(
        min_doc_freq=args.min_doc_freq,
        uni_topn=args.uni_topn,
        bigram_topk=args.bigram_topk,
        bigram_min_count=args.bigram_min_count,
        trigram_topk=args.trigram_topk,
        trigram_min_count=args.trigram_min_count,
        trigram_cap=args.trigram_cap,
        sample_seed=args.sample_seed,
    )
    w1_report = write_pack(counter, args.out_dir / "pack-w1", count_index=0, **pack_kwargs)
    print(f"wrote pack-w1 to {args.out_dir / 'pack-w1'}", file=sys.stderr)
    w2_report = write_pack(counter, args.out_dir / "pack-w2", count_index=1, **pack_kwargs)
    print(f"wrote pack-w2 to {args.out_dir / 'pack-w2'}", file=sys.stderr)

    total_elapsed = time.time() - t0
    report = {
        "shard_path": str(args.shard),
        "shard_sha256": shard_sha256,
        "docs_processed": counter.docs_seen,
        "doc_filter": "off" if args.no_doc_filter else doc_filter_counts,
        "valid_words_counted": counter.valid_words_seen,
        "sentences_total": counter.sentences_total,
        "sentences_short_always_counted": counter.sentences_short,
        "sentences_duplicate_skipped": counter.sentences_duplicate,
        "duplicate_ratio_of_total_sentences": dup_ratio,
        "sentences_counted": counter.sentences_counted,
        "colloquial_sentences": counter.colloquial_sentences,
        "colloquial_ratio_of_counted_sentences": colloquial_ratio,
        "unique_unigrams_seen": len(counter.uni),
        "unique_bigrams_seen": len(counter.bi),
        "unique_trigrams_seen": len(counter.tri),
        "bi_prune_events": counter.bi_prune_events,
        "bi_pruned_total": counter.bi_pruned_total,
        "tri_prune_events": counter.tri_prune_events,
        "tri_pruned_total": counter.tri_pruned_total,
        "counting_elapsed_seconds": counting_elapsed,
        "total_elapsed_seconds": total_elapsed,
        "peak_rss_mb": peak_rss,
        "pack_w1": w1_report,
        "pack_w2": w2_report,
        "args": {k: (str(v) if isinstance(v, Path) else v) for k, v in vars(args).items()},
    }
    report_path = args.out_dir / "build_report.json"
    report_path.write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
    print(f"wrote report to {report_path}", file=sys.stderr)


if __name__ == "__main__":
    main()
