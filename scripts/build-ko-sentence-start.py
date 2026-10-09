#!/usr/bin/env python3
# Source: HuggingFaceFW/fineweb-2 (config kor_Hang), License: ODC-By 1.0, https://huggingface.co/datasets/HuggingFaceFW/fineweb-2
#         + songys/Chatbot_data (MIT) casual layer, split rule shared with scripts/merge-ko-casual-layer.py
"""FineWeb-2 한국어에서 문장 시작(BOS) 분포를 집계해 기본 한국어 팩에 얹을 데이터를 만든다.

토큰화·문장 분리·문장 중복 제거·유효 어절 판정·구어 가중·문서 품질 필터·목표 어절 수 집계는
scripts/build-ko-corpus-ngram.py의 함수를 그대로 쓴다. 이 스크립트가 더하는 규칙은 다음뿐이다.

BOS 표본
  문장의 첫 원시 토큰이 유효 어절일 때만 그 문장은 BOS 표본이다. 첫 토큰이 버려졌는데 뒤 run의
  첫 어절을 BOS로 세지 않는다. w1은 첫 어절, w2는 같은 run의 둘째 어절(있으면)이다.
  문장 중복 제거에 걸린 문장은 표본이 아니다(어절이 2개 이상인 문장만 해시 대상, 기존과 같다).

웹 집계
  가중치는 기존 w2 방식(구어 어미 문장 3배)이다. 문서빈도 3 이상, 가중 빈도 5 이상만 남긴다.
  w1과 w2는 app/src/main/assets/ko_base_vocab.tsv 안에 있어야 하고, w2가 pack-ko-base-assets.py의
  NEXT_EXCLUDE에 걸리면 버린다. 이 집계는 λ와 무관하므로 --raw-dir에 한 번만 쓰고 다시 쓴다.
  문서를 여러 프로세스로 나눠 읽지만 문장 중복 제거와 집계는 문서 순서대로 한 프로세스에서 하므로
  결과는 단일 프로세스와 같다.

구어 층
  ChatbotData.csv 학습 분할(질문 blake2b 해시 mod 5 != 0, merge-ko-casual-layer.py와 같은 규칙)의 Q·A
  문장을 같은 토큰화로 집계하고 2회 이상만 남긴다(중복 제거·문서 필터 없음).

최종 분포
  p = (1 - lam) * p_web + lam * p_casual. 확률은 각 분포의 남은 항목 합으로 정규화한다.
  bos_bigram의 w1별 조건 분포는 같은 식을 쓰되, 그 w1에 구어 관측이 없으면 웹 분포만 쓴다.

출력(lam별 디렉터리 <out-dir>/lam_<값>/)
  bos_unigram.tsv  w1, count(= round(p * 1e6)), prob   상위 2000
  bos_bigram.tsv   w1, w2, count(= round(q * 1e6))     BOS 상위 1000개 w1에 대해 w1당 5개
"""

from __future__ import annotations

import argparse
import csv
import hashlib
import importlib.util
import json
import multiprocessing
import os
import sys
import time
from collections import Counter, deque
from pathlib import Path

SCRIPT_DIR = Path(__file__).resolve().parent
REPO_ROOT = SCRIPT_DIR.parent

PROB_SCALE = 1_000_000
UNIGRAM_ROWS = 2000
BIGRAM_FIRST_WORDS = 1000
BIGRAM_PER_FIRST = 5
MIN_DOC_FREQ = 3
MIN_WEIGHTED_COUNT = 5
MIN_CASUAL_COUNT = 2
DOCS_PER_TASK = 64
MAX_PENDING_TASKS = 64


def load_script(filename: str, module_name: str):
    path = SCRIPT_DIR / filename
    spec = importlib.util.spec_from_file_location(module_name, path)
    module = importlib.util.module_from_spec(spec)
    assert spec.loader is not None
    spec.loader.exec_module(module)
    return module


builder = load_script("build-ko-corpus-ngram.py", "build_ko_corpus_ngram")
packer = load_script("pack-ko-base-assets.py", "pack_ko_base_assets")
merger = load_script("merge-ko-casual-layer.py", "merge_ko_casual_layer")

NEXT_EXCLUDE = packer.NEXT_EXCLUDE
VOCAB_PATH = REPO_ROOT / "app/src/main/assets/ko_base_vocab.tsv"


def load_vocab_order(path: Path = VOCAB_PATH) -> list[tuple[str, int]]:
    rows: list[tuple[str, int]] = []
    for line in path.read_text(encoding="utf-8").splitlines():
        if line.startswith("#") or not line.strip():
            continue
        word, count = line.split("\t")
        rows.append((word, int(count)))
    return rows


def sentence_start(sentence: str, runs: list[list[str]]) -> tuple[str, str | None] | None:
    """(w1, w2) if the sentence is a BOS sample, else None. The first raw token must itself be a valid
    eojeol; otherwise the first run begins later in the sentence and is not a sentence start."""
    if not runs:
        return None
    first_token = sentence.split(None, 1)[0]
    if builder.clean_token(first_token) is None:
        return None
    run = runs[0]
    return run[0], (run[1] if len(run) > 1 else None)


def text_sentence_starts(text: str) -> list[tuple[str, str | None]]:
    starts: list[tuple[str, str | None]] = []
    for sentence, runs in builder.iter_sentences(text):
        start = sentence_start(sentence, runs)
        if start is not None:
            starts.append(start)
    return starts


def scan_documents(texts: list[str], doc_filter: bool):
    """Worker: per document, (quality verdict reason, kept?, sentence total, sentence records). A record is
    (dedup hash or None, valid word count, colloquial?, BOS (w1, w2) or None). Dedup and counting stay in
    the parent so the result does not depend on how documents are split across workers."""
    out = []
    for text in texts:
        if doc_filter:
            verdict = builder.assess_document(text)
            reason, keep = verdict.reason, verdict.keep
        else:
            reason, keep = None, True
        if not keep:
            out.append((reason, False, 0, []))
            continue
        total = 0
        records = []
        for sentence, runs in builder.iter_sentences(text):
            total += 1
            if not runs:
                continue
            valid = sum(len(r) for r in runs)
            digest = None
            if valid >= 2:
                norm = builder.normalize_sentence(sentence)
                digest = hashlib.blake2b(norm.encode("utf-8"), digest_size=8).digest()
            colloquial = builder.is_colloquial_ending(runs[-1][-1])
            records.append((digest, valid, colloquial, sentence_start(sentence, runs)))
        out.append((reason, True, total, records))
    return out


def iter_document_chunks(shard: Path):
    chunk: list[str] = []
    for text in builder.iter_shard_texts(shard):
        chunk.append(text)
        if len(chunk) >= DOCS_PER_TASK:
            yield chunk
            chunk = []
    if chunk:
        yield chunk


def count_web_sentence_starts(args, vocab: set[str]) -> dict:
    seen: set[bytes] = set()
    uni: dict[str, list[int]] = {}
    bi: dict[tuple[str, str], list[int]] = {}
    stats = Counter()
    doc_filter_counts: Counter = Counter()
    t0 = time.time()
    last_report = t0
    done = False

    def consume(doc_results) -> bool:
        nonlocal last_report
        for reason, keep, total, records in doc_results:
            if not args.no_doc_filter:
                doc_filter_counts[reason] += 1
            if not keep:
                continue
            stats["docs_seen"] += 1
            stats["sentences_total"] += total
            doc_uni: set[str] = set()
            doc_bi: set[tuple[str, str]] = set()
            for digest, valid, colloquial, start in records:
                if digest is not None:
                    if digest in seen:
                        stats["sentences_duplicate"] += 1
                        continue
                    seen.add(digest)
                else:
                    stats["sentences_short"] += 1
                stats["sentences_counted"] += 1
                stats["valid_words_seen"] += valid
                if colloquial:
                    stats["colloquial_sentences"] += 1
                if start is None:
                    continue
                w1, w2 = start
                stats["bos_sentences"] += 1
                if w1 not in vocab:
                    continue
                weight = builder.COLLOQUIAL_WEIGHT if colloquial else 1
                entry = uni.setdefault(w1, [0, 0, 0])
                entry[0] += 1
                entry[1] += weight
                doc_uni.add(w1)
                if w2 is not None and w2 in vocab and w2 not in NEXT_EXCLUDE:
                    pair = (w1, w2)
                    entry_b = bi.setdefault(pair, [0, 0, 0])
                    entry_b[0] += 1
                    entry_b[1] += weight
                    doc_bi.add(pair)
            for w in doc_uni:
                uni[w][2] += 1
            for pair in doc_bi:
                bi[pair][2] += 1
            if stats["valid_words_seen"] >= args.target_words:
                return True
            if args.max_docs and stats["docs_seen"] >= args.max_docs:
                return True
        if time.time() - last_report > 15:
            print(
                f"docs={stats['docs_seen']} valid_words={stats['valid_words_seen']} "
                f"sent_total={stats['sentences_total']} sent_dup={stats['sentences_duplicate']} "
                f"bos={stats['bos_sentences']} uni={len(uni)} bi={len(bi)} "
                f"elapsed_s={time.time() - t0:.1f}",
                file=sys.stderr,
                flush=True,
            )
            last_report = time.time()
        return False

    workers = args.workers or max(1, min(16, (os.cpu_count() or 4) - 4))
    ctx = multiprocessing.get_context("spawn")
    pool = ctx.Pool(workers)
    pending: deque = deque()
    for chunk in iter_document_chunks(args.shard):
        pending.append(pool.apply_async(scan_documents, (chunk, not args.no_doc_filter)))
        if len(pending) >= MAX_PENDING_TASKS:
            done = consume(pending.popleft().get())
            if done:
                break
    while pending and not done:
        done = consume(pending.popleft().get())
    pool.close()
    for task in pending:
        task.wait()
    pool.join()

    stats["counting_seconds"] = round(time.time() - t0, 1)
    uni_kept = {w: v for w, v in uni.items() if v[2] >= MIN_DOC_FREQ and v[1] >= MIN_WEIGHTED_COUNT}
    bi_kept = {p: v for p, v in bi.items() if v[2] >= MIN_DOC_FREQ and v[1] >= MIN_WEIGHTED_COUNT}
    return {
        "stats": dict(stats),
        "doc_filter": "off" if args.no_doc_filter else dict(doc_filter_counts),
        "uni_unique": len(uni),
        "bi_unique": len(bi),
        "uni": uni_kept,
        "bi": bi_kept,
    }


def write_raw_cache(raw_dir: Path, result: dict, meta: dict) -> None:
    raw_dir.mkdir(parents=True, exist_ok=True)
    with (raw_dir / "web_bos_unigram_raw.tsv").open("w", encoding="utf-8", newline="\n") as f:
        for w, (raw, weighted, docs) in sorted(result["uni"].items(), key=lambda kv: (-kv[1][1], kv[0].encode("utf-8"))):
            f.write(f"{w}\t{raw}\t{weighted}\t{docs}\n")
    with (raw_dir / "web_bos_bigram_raw.tsv").open("w", encoding="utf-8", newline="\n") as f:
        for (w1, w2), (raw, weighted, docs) in sorted(
            result["bi"].items(), key=lambda kv: (-kv[1][1], kv[0][0].encode("utf-8"), kv[0][1].encode("utf-8"))
        ):
            f.write(f"{w1}\t{w2}\t{raw}\t{weighted}\t{docs}\n")
    meta = dict(meta)
    meta.update(
        stats=result["stats"],
        doc_filter=result["doc_filter"],
        uni_unique_before_filter=result["uni_unique"],
        bi_unique_before_filter=result["bi_unique"],
        uni_kept=len(result["uni"]),
        bi_kept=len(result["bi"]),
    )
    (raw_dir / "web_bos_meta.json").write_text(json.dumps(meta, ensure_ascii=False, indent=2), encoding="utf-8")


def read_raw_cache(raw_dir: Path) -> tuple[dict[str, int], dict[str, dict[str, int]], dict]:
    """Weighted web counts: w1 -> count, w1 -> {w2: count}, and the cache metadata."""
    meta = json.loads((raw_dir / "web_bos_meta.json").read_text(encoding="utf-8"))
    uni: dict[str, int] = {}
    with (raw_dir / "web_bos_unigram_raw.tsv").open(encoding="utf-8") as f:
        for line in f:
            w, _raw, weighted, _docs = line.rstrip("\n").split("\t")
            uni[w] = int(weighted)
    bi: dict[str, dict[str, int]] = {}
    with (raw_dir / "web_bos_bigram_raw.tsv").open(encoding="utf-8") as f:
        for line in f:
            w1, w2, _raw, weighted, _docs = line.rstrip("\n").split("\t")
            bi.setdefault(w1, {})[w2] = int(weighted)
    return uni, bi, meta


def count_casual_sentence_starts(csv_path: Path, vocab: set[str]) -> tuple[dict[str, int], dict[str, dict[str, int]], dict]:
    uni: Counter = Counter()
    bi: dict[str, Counter] = {}
    sentences = 0
    with csv_path.open(encoding="utf-8", newline="") as f:
        for row in csv.DictReader(f):
            if merger.is_holdout(row["Q"]):
                continue
            for field in ("Q", "A"):
                for w1, w2 in text_sentence_starts(row[field]):
                    sentences += 1
                    if w1 not in vocab:
                        continue
                    uni[w1] += 1
                    if w2 is not None and w2 in vocab and w2 not in NEXT_EXCLUDE:
                        bi.setdefault(w1, Counter())[w2] += 1
    uni_kept = {w: c for w, c in uni.items() if c >= MIN_CASUAL_COUNT}
    bi_kept: dict[str, dict[str, int]] = {}
    for w1, counter in bi.items():
        kept = {w: c for w, c in counter.items() if c >= MIN_CASUAL_COUNT}
        if kept:
            bi_kept[w1] = kept
    return uni_kept, bi_kept, {"bos_sentences": sentences, "uni_kept": len(uni_kept), "bi_first_words": len(bi_kept)}


def normalized(counts: dict[str, int]) -> dict[str, float]:
    total = sum(counts.values())
    return {w: c / total for w, c in counts.items()} if total else {}


def blend(web: dict[str, float], casual: dict[str, float], lam: float) -> dict[str, float]:
    scores: dict[str, float] = {}
    for w, p in web.items():
        scores[w] = (1.0 - lam) * p
    for w, p in casual.items():
        scores[w] = scores.get(w, 0.0) + lam * p
    return scores


def ranked(scores: dict[str, float]) -> list[tuple[str, float]]:
    return sorted(scores.items(), key=lambda t: (-t[1], t[0].encode("utf-8")))


def build_distribution(web_uni, web_bi, cas_uni, cas_bi, lam: float):
    uni_scores = blend(normalized(web_uni), normalized(cas_uni), lam)
    uni_rows = [(w, p) for w, p in ranked(uni_scores) if p > 0][:UNIGRAM_ROWS]
    bi_rows: list[tuple[str, str, float]] = []
    for w1, _p in uni_rows[:BIGRAM_FIRST_WORDS]:
        web = normalized(web_bi.get(w1, {}))
        casual = normalized(cas_bi.get(w1, {}))
        scores = blend(web, casual, lam) if casual else web
        for w2, q in ranked(scores)[:BIGRAM_PER_FIRST]:
            if q > 0:
                bi_rows.append((w1, w2, q))
    return uni_rows, bi_rows


def write_distribution(out_dir: Path, uni_rows, bi_rows) -> None:
    out_dir.mkdir(parents=True, exist_ok=True)
    with (out_dir / "bos_unigram.tsv").open("w", encoding="utf-8", newline="\n") as f:
        for w, p in uni_rows:
            f.write(f"{w}\t{max(1, round(p * PROB_SCALE))}\t{p:.8g}\n")
    with (out_dir / "bos_bigram.tsv").open("w", encoding="utf-8", newline="\n") as f:
        for w1, w2, q in bi_rows:
            f.write(f"{w1}\t{w2}\t{max(1, round(q * PROB_SCALE))}\n")


def lam_dir_name(lam: float) -> str:
    return f"lam_{lam:g}"


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--shard", type=Path, required=True, help="FineWeb-2 kor_Hang train parquet shard")
    parser.add_argument("--chatbot-csv", type=Path, required=True, help="songys/Chatbot_data ChatbotData.csv")
    parser.add_argument("--out-dir", type=Path, required=True, help="parent dir; lam_<값>/ 디렉터리가 만들어진다")
    parser.add_argument("--raw-dir", type=Path, required=True, help="웹 원집계 캐시 디렉터리(있으면 재사용)")
    parser.add_argument("--lam", type=float, nargs="+", default=[0.3], help="구어 층 비중 λ (여러 개 가능)")
    parser.add_argument("--target-words", type=int, default=200_000_000)
    parser.add_argument("--max-docs", type=int, default=0)
    parser.add_argument("--workers", type=int, default=0, help="0 = min(16, CPU-4)")
    parser.add_argument("--no-doc-filter", action="store_true")
    parser.add_argument("--rebuild-web", action="store_true", help="캐시가 있어도 웹 집계를 다시 돌린다")
    args = parser.parse_args()

    vocab_rows = load_vocab_order()
    vocab = {w for w, _ in vocab_rows}
    vocab_sha = builder.sha256_of(VOCAB_PATH)

    cache_ready = (args.raw_dir / "web_bos_meta.json").is_file() and not args.rebuild_web
    if cache_ready:
        web_uni, web_bi, meta = read_raw_cache(args.raw_dir)
        expected = {"vocab_sha256": vocab_sha, "target_words": args.target_words, "max_docs": args.max_docs,
                    "no_doc_filter": args.no_doc_filter}
        mismatched = {k: (meta.get(k), v) for k, v in expected.items() if meta.get(k) != v}
        if mismatched:
            raise SystemExit(f"웹 원집계 캐시 조건이 다르다(--rebuild-web 필요): {mismatched}")
        print(f"웹 원집계 캐시 사용: {args.raw_dir} (shard sha256={meta['shard_sha256']})", file=sys.stderr)
    else:
        shard_sha = builder.sha256_of(args.shard)
        print(f"shard={args.shard} sha256={shard_sha}", file=sys.stderr)
        result = count_web_sentence_starts(args, vocab)
        meta = {
            "shard_path": str(args.shard),
            "shard_sha256": shard_sha,
            "vocab_sha256": vocab_sha,
            "target_words": args.target_words,
            "max_docs": args.max_docs,
            "no_doc_filter": args.no_doc_filter,
            "min_doc_freq": MIN_DOC_FREQ,
            "min_weighted_count": MIN_WEIGHTED_COUNT,
        }
        write_raw_cache(args.raw_dir, result, meta)
        web_uni, web_bi, meta = read_raw_cache(args.raw_dir)
        print(f"DONE {json.dumps(meta['stats'], ensure_ascii=False)}", file=sys.stderr)
        print(f"웹 원집계 기록: {args.raw_dir} (w1 {len(web_uni)}개, (w1,w2) {sum(len(v) for v in web_bi.values())}개)", file=sys.stderr)

    cas_uni, cas_bi, cas_stats = count_casual_sentence_starts(args.chatbot_csv, vocab)
    print(f"구어 층: {json.dumps(cas_stats, ensure_ascii=False)}", file=sys.stderr)

    for lam in args.lam:
        uni_rows, bi_rows = build_distribution(web_uni, web_bi, cas_uni, cas_bi, lam)
        out = args.out_dir / lam_dir_name(lam)
        write_distribution(out, uni_rows, bi_rows)
        print(f"lam={lam:g}: bos_unigram {len(uni_rows)}행, bos_bigram {len(bi_rows)}행 -> {out}")


if __name__ == "__main__":
    main()
