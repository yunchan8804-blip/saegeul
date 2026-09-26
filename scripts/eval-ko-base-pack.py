#!/usr/bin/env python3
# Evaluation harness comparing the bundled ko_base_vocab.tsv / nextword.txt
# against candidate FineWeb-2 (kor_Hang, ODC-By 1.0) n-gram packs built by
# build-ko-corpus-ngram.py. Reads-only against the repo; writes only under
# the scratchpad output directory.

"""Compares:
  - current: app/src/main/assets/ko_base_vocab.tsv (30k words) +
             plugin/hangul/.../data/nextword.txt (~1.2k prev->candidates)
  - candidate: a single unigram.tsv / bigram.tsv / trigram.tsv pack directory
    from build-ko-corpus-ngram.py (run once per weight variant, e.g.
    pack-w1/ and pack-w2/)

against three eval sets:
  E1 FineWeb-2 kor_Hang test shard (held-out, >= 1,000,000 valid words)
  E2 songys/Chatbot_data ChatbotData.csv (Q+A sentences)
  E3 app/src/main/assets/sentence-packs/ko-basic-v1.txt (216 sentences)

Tokenization for the eval sets reuses the same sentence/eojeol validity rule
as the builder (see build-ko-corpus-ngram.py docstring) so coverage numbers
are apples-to-apples with how the packs were built.
"""

from __future__ import annotations

import argparse
import csv
import importlib.util
import json
import random
import sys
import time
from collections import defaultdict
from pathlib import Path

import pyarrow.parquet as pq

REPO_ROOT = Path("D:/workspace/Saegul")


def _load_sentence_runs():
    """Reuse the exact tokenizer from build-ko-corpus-ngram.py (same file,
    hyphenated name, loaded by path since it is not an importable module
    name) so eval coverage is computed with the identical tokenization rule
    used to build the candidate packs."""
    builder_path = Path(__file__).resolve().parent / "build-ko-corpus-ngram.py"
    spec = importlib.util.spec_from_file_location("build_ko_corpus_ngram", builder_path)
    module = importlib.util.module_from_spec(spec)
    assert spec.loader is not None
    spec.loader.exec_module(module)
    return module.sentence_runs


sentence_runs = _load_sentence_runs()


def load_current_vocab(path: Path) -> dict[str, int]:
    vocab: dict[str, int] = {}
    for line in path.read_text(encoding="utf-8").splitlines():
        if line.startswith("#") or not line.strip():
            continue
        parts = line.split("\t")
        if len(parts) != 2:
            continue
        word, freq = parts
        try:
            vocab[word] = int(freq)
        except ValueError:
            continue
    return vocab


def load_current_nextword(path: Path) -> dict[str, list[str]]:
    table: dict[str, list[str]] = {}
    for line in path.read_text(encoding="utf-8").splitlines():
        if line.startswith("#") or not line.strip():
            continue
        parts = line.split("\t")
        if len(parts) < 2:
            continue
        prev, cands = parts[0], parts[1:]
        table[prev] = cands
    return table


def load_candidate_unigram(path: Path) -> dict[str, int]:
    vocab: dict[str, int] = {}
    for line in path.read_text(encoding="utf-8").splitlines():
        parts = line.split("\t")
        if len(parts) < 2:
            continue
        word = parts[0]
        count = int(parts[1])
        vocab[word] = count
    return vocab


def load_candidate_bigram(path: Path) -> dict[str, list[tuple[str, int]]]:
    table: dict[str, list[tuple[str, int]]] = defaultdict(list)
    for line in path.read_text(encoding="utf-8").splitlines():
        parts = line.split("\t")
        if len(parts) != 3:
            continue
        w1, w2, count = parts[0], parts[1], int(parts[2])
        table[w1].append((w2, count))
    for w1 in table:
        table[w1].sort(key=lambda t: t[1], reverse=True)
    return table


def load_bigram_rows(path: Path) -> list[tuple[str, str, int]]:
    rows = []
    for line in path.read_text(encoding="utf-8").splitlines():
        parts = line.split("\t")
        if len(parts) != 3:
            continue
        rows.append((parts[0], parts[1], int(parts[2])))
    return rows


def load_trigram_rows(path: Path) -> list[tuple[str, str, str, int]]:
    rows = []
    for line in path.read_text(encoding="utf-8").splitlines():
        parts = line.split("\t")
        if len(parts) != 4:
            continue
        rows.append((parts[0], parts[1], parts[2], int(parts[3])))
    return rows


def load_candidate_trigram(path: Path) -> dict[tuple[str, str], list[tuple[str, int]]]:
    table: dict[tuple[str, str], list[tuple[str, int]]] = defaultdict(list)
    for line in path.read_text(encoding="utf-8").splitlines():
        parts = line.split("\t")
        if len(parts) != 4:
            continue
        w1, w2, w3, count = parts[0], parts[1], parts[2], int(parts[3])
        table[(w1, w2)].append((w3, count))
    for k in table:
        table[k].sort(key=lambda t: t[1], reverse=True)
    return table


def tokenize_text_to_sentences(text: str) -> list[list[str]]:
    return sentence_runs(text)


def load_eval_e1(shard_path: Path, target_words: int) -> list[list[str]]:
    pf = pq.ParquetFile(shard_path)
    sentences: list[list[str]] = []
    total_words = 0
    for batch in pf.iter_batches(batch_size=2048, columns=["text"]):
        for text in batch.column("text").to_pylist():
            for run in tokenize_text_to_sentences(text):
                sentences.append(run)
                total_words += len(run)
            if total_words >= target_words:
                break
        if total_words >= target_words:
            break
    return sentences


def load_eval_e2(csv_path: Path) -> list[list[str]]:
    sentences: list[list[str]] = []
    with csv_path.open(encoding="utf-8", newline="") as f:
        reader = csv.DictReader(f)
        for row in reader:
            for field in ("Q", "A"):
                text = row.get(field, "")
                if not text:
                    continue
                for run in tokenize_text_to_sentences(text):
                    sentences.append(run)
    return sentences


def load_eval_e3(path: Path) -> list[list[str]]:
    sentences: list[list[str]] = []
    for line in path.read_text(encoding="utf-8").splitlines():
        if not line.strip():
            continue
        for run in tokenize_text_to_sentences(line):
            sentences.append(run)
    return sentences


def coverage(sentences: list[list[str]], vocab: dict[str, int]) -> float:
    total = 0
    hit = 0
    for run in sentences:
        for w in run:
            total += 1
            if w in vocab:
                hit += 1
    return hit / total if total else 0.0


def prefix_completion_rate(sentences: list[list[str]], vocab: dict[str, int], prefix_len: int, topk: int) -> float:
    ranked = sorted(vocab.items(), key=lambda t: t[1], reverse=True)
    by_prefix: dict[str, list[str]] = defaultdict(list)
    for w, _c in ranked:
        if len(w) < prefix_len:
            continue
        p = w[:prefix_len]
        if len(by_prefix[p]) < topk:
            by_prefix[p].append(w)
    total = 0
    hit = 0
    for run in sentences:
        for w in run:
            if len(w) < 2:
                continue
            total += 1
            p = w[:prefix_len]
            if len(w) >= prefix_len and w in by_prefix.get(p, []):
                hit += 1
    return hit / total if total else 0.0


def _merged_candidates(
    prev: str,
    bi_cands: list[str],
    backoff_cands: list[str],
    current_nextword: dict[str, list[str]],
    topk: int = 5,
) -> list[str]:
    """current nextword.txt candidates first, then fill remaining slots (up
    to topk total) from the trigram-backoff/bigram candidates, skipping
    anything already present."""
    merged = list(current_nextword.get(prev, [])[:topk])
    seen = set(merged)
    for w in backoff_cands:
        if len(merged) >= topk:
            break
        if w in seen:
            continue
        merged.append(w)
        seen.add(w)
    return merged


def nextword_hit_rates(
    sentences: list[list[str]],
    current_nextword: dict[str, list[str]],
    bigram_table: dict[str, list[tuple[str, int]]],
    trigram_table: dict[tuple[str, str], list[tuple[str, int]]],
    ks: tuple[int, ...] = (1, 3, 5),
) -> dict[str, dict]:
    pairs = 0
    cur_hit = {k: 0 for k in ks}
    cur_provided = 0
    bi_hit = {k: 0 for k in ks}
    bi_provided = 0
    tri_hit = {k: 0 for k in ks}
    tri_provided = 0
    merged_hit = {k: 0 for k in ks}
    merged_provided = 0

    for run in sentences:
        for i in range(len(run) - 1):
            prev, nxt = run[i], run[i + 1]
            pairs += 1
            cur_cands = current_nextword.get(prev, [])
            if cur_cands:
                cur_provided += 1
                for k in ks:
                    if nxt in cur_cands[:k]:
                        cur_hit[k] += 1
            bi_cands = [w for w, _c in bigram_table.get(prev, [])]
            if bi_cands:
                bi_provided += 1
                for k in ks:
                    if nxt in bi_cands[:k]:
                        bi_hit[k] += 1
            # Backoff candidates: trigram when two preceding words exist and
            # the pair is in the trigram table, otherwise fall back to the
            # same bigram candidates used above. Computed for every pair
            # (including i == 0, where trigram lookup is simply unavailable
            # and the backoff degrades to plain bigram) so this is directly
            # comparable to candidate_bigram over the same denominator.
            if i >= 1:
                prev2, prev1 = run[i - 1], run[i]
                tri_cands = [w for w, _c in trigram_table.get((prev2, prev1), [])]
            else:
                tri_cands = []
            backoff_cands = tri_cands if tri_cands else bi_cands
            if backoff_cands:
                tri_provided += 1
                for k in ks:
                    if nxt in backoff_cands[:k]:
                        tri_hit[k] += 1

            merged_cands = _merged_candidates(prev, bi_cands, backoff_cands, current_nextword)
            if merged_cands:
                merged_provided += 1
                for k in ks:
                    if nxt in merged_cands[:k]:
                        merged_hit[k] += 1

    def rate(hitmap, denom):
        return {f"hit@{k}": (hitmap[k] / denom if denom else 0.0) for k in ks}

    return {
        "pairs": pairs,
        "current": {
            "coverage_rate": cur_provided / pairs if pairs else 0.0,
            **rate(cur_hit, pairs),
        },
        "candidate_bigram": {
            "coverage_rate": bi_provided / pairs if pairs else 0.0,
            **rate(bi_hit, pairs),
        },
        "candidate_bigram_trigram_backoff": {
            "coverage_rate": tri_provided / pairs if pairs else 0.0,
            **rate(tri_hit, pairs),
        },
        "merged_current_then_backoff": {
            "coverage_rate": merged_provided / pairs if pairs else 0.0,
            **rate(merged_hit, pairs),
        },
    }


def next_candidates_for_context(
    context: str,
    current_nextword: dict[str, list[str]],
    bigram_table: dict[str, list[tuple[str, int]]],
    trigram_table: dict[tuple[str, str], list[tuple[str, int]]],
    topk: int = 5,
) -> dict:
    tokens = context.split()
    last = tokens[-1] if tokens else ""
    cur = current_nextword.get(last, [])[:topk]
    bi = [w for w, _c in bigram_table.get(last, [])][:topk]
    tri_cands = []
    if len(tokens) >= 2:
        tri_cands = [w for w, _c in trigram_table.get((tokens[-2], tokens[-1]), [])]
    backoff_full = tri_cands if tri_cands else [w for w, _c in bigram_table.get(last, [])]
    backoff = backoff_full[:topk]
    merged = _merged_candidates(last, bi, backoff_full, current_nextword, topk=topk)
    return {
        "current_nextword": cur,
        "candidate_bigram": bi,
        "candidate_bigram_trigram_backoff": backoff,
        "merged_current_then_backoff": merged,
    }


VOCAB_CHECK_WORDS = [
    "퇴근", "퇴근길", "카톡", "택배", "배송", "결제", "회의록", "단톡", "맛집", "회의실",
    "연락드릴게요", "보내드릴게요", "공유드립니다", "퇴근하고", "주말에", "내일까지",
    "넵", "네네", "오케이", "이메일",
]

PROBE_CONTEXTS = [
    "안녕하세요", "지금", "지금 뭐해", "오늘", "내일", "퇴근하고", "회의",
    "감사합니다", "혹시", "그럼", "밥", "주말에",
    "뭐해", "밥 먹었어", "퇴근", "오늘 저녁", "잠깐만",
]


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--pack-dir", type=Path, required=True, help="directory containing unigram.tsv / bigram.tsv / trigram.tsv")
    parser.add_argument("--unigram", type=Path, required=True)
    parser.add_argument("--bigram", type=Path, required=True)
    parser.add_argument("--trigram", type=Path, required=True)
    parser.add_argument("--test-shard", type=Path, required=True)
    parser.add_argument("--chatbot-csv", type=Path, required=True)
    parser.add_argument("--e1-target-words", type=int, default=1_000_000)
    parser.add_argument("--out", type=Path, required=True)
    parser.add_argument("--sample-seed", type=int, default=20260924)
    args = parser.parse_args()

    t0 = time.time()

    current_vocab = load_current_vocab(REPO_ROOT / "app/src/main/assets/ko_base_vocab.tsv")
    current_nextword = load_current_nextword(
        REPO_ROOT / "plugin/hangul/src/main/cpp/fcitx5-hangul/data/nextword.txt"
    )
    cand_uni = load_candidate_unigram(args.unigram)
    cand_bigram = load_candidate_bigram(args.bigram)
    cand_trigram = load_candidate_trigram(args.trigram)

    print(f"loaded vocabs in {time.time() - t0:.1f}s", file=sys.stderr)

    e1 = load_eval_e1(args.test_shard, args.e1_target_words)
    e2 = load_eval_e2(args.chatbot_csv)
    e3 = load_eval_e3(REPO_ROOT / "app/src/main/assets/sentence-packs/ko-basic-v1.txt")
    print(
        f"eval sets loaded: E1 sentences={len(e1)} words={sum(len(s) for s in e1)}, "
        f"E2 sentences={len(e2)} words={sum(len(s) for s in e2)}, "
        f"E3 sentences={len(e3)} words={sum(len(s) for s in e3)} "
        f"({time.time() - t0:.1f}s)",
        file=sys.stderr,
    )

    eval_sets = {"E1_fineweb_test": e1, "E2_chatbot": e2, "E3_sentence_pack": e3}

    metric1_coverage = {}
    for name, sset in eval_sets.items():
        metric1_coverage[name] = {
            "current_30k": coverage(sset, current_vocab),
            "candidate": coverage(sset, cand_uni),
        }

    metric2_completion = {}
    for name, sset in eval_sets.items():
        metric2_completion[name] = {
            "prefix1_top4": {
                "current_30k": prefix_completion_rate(sset, current_vocab, 1, 4),
                "candidate": prefix_completion_rate(sset, cand_uni, 1, 4),
            },
            "prefix2_top4": {
                "current_30k": prefix_completion_rate(sset, current_vocab, 2, 4),
                "candidate": prefix_completion_rate(sset, cand_uni, 2, 4),
            },
        }

    metric3_nextword = {}
    for name, sset in eval_sets.items():
        metric3_nextword[name] = nextword_hit_rates(sset, current_nextword, cand_bigram, cand_trigram)

    metric4_probes = {}
    for ctx in PROBE_CONTEXTS:
        metric4_probes[ctx] = next_candidates_for_context(ctx, current_nextword, cand_bigram, cand_trigram, topk=5)

    metric5_vocab_check = {}
    for w in VOCAB_CHECK_WORDS:
        metric5_vocab_check[w] = {
            "current_30k": w in current_vocab,
            "candidate": w in cand_uni,
        }

    rng = random.Random(args.sample_seed)
    uni_ranked = sorted(cand_uni.items(), key=lambda t: t[1], reverse=True)
    bigram_rows = load_bigram_rows(args.bigram)
    trigram_rows = load_trigram_rows(args.trigram)
    # bigram.tsv / trigram.tsv are written frequency-descending by the
    # builder, so the first rows are already the top-by-frequency sample.
    metric6_samples = {
        "unigram_top50": [w for w, _c in uni_ranked[:50]],
        "bigram_random30": [
            {"prev": w1, "next": w2, "count": c}
            for w1, w2, c in rng.sample(bigram_rows, min(30, len(bigram_rows)))
        ],
        "bigram_top30_by_frequency": [
            {"prev": w1, "next": w2, "count": c} for w1, w2, c in bigram_rows[:30]
        ],
        "trigram_random20": [
            {"prev2": w1, "prev1": w2, "next": w3, "count": c}
            for w1, w2, w3, c in rng.sample(trigram_rows, min(20, len(trigram_rows)))
        ],
        "trigram_top30_by_frequency": [
            {"prev2": w1, "prev1": w2, "next": w3, "count": c} for w1, w2, w3, c in trigram_rows[:30]
        ],
    }

    result = {
        "eval_set_sizes": {
            name: {"sentences": len(s), "words": sum(len(x) for x in s)} for name, s in eval_sets.items()
        },
        "metric1_word_coverage": metric1_coverage,
        "metric2_prefix_completion": metric2_completion,
        "metric3_nextword_hit_rates": metric3_nextword,
        "metric4_probe_candidates": metric4_probes,
        "metric5_vocab_presence": metric5_vocab_check,
        "metric6_quality_samples": metric6_samples,
        "elapsed_seconds": time.time() - t0,
    }
    args.out.parent.mkdir(parents=True, exist_ok=True)
    args.out.write_text(json.dumps(result, ensure_ascii=False, indent=2), encoding="utf-8")
    print(f"wrote eval result to {args.out} ({time.time() - t0:.1f}s)", file=sys.stderr)


if __name__ == "__main__":
    main()
