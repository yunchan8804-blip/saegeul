#!/usr/bin/env python3
# Sources: web pack from build-ko-corpus-ngram.py (FineWeb-2, ODC-By 1.0) + songys/Chatbot_data ChatbotData.csv (MIT)
#          + the project's own hand-written next-word list (fcitx5-hangul data/nextword.txt).
"""FineWeb-2 웹 팩에 구어 층을 섞어 기본 한국어 팩을 만든다.

웹 코퍼스는 문어체라 채팅 문맥의 다음 어절이 약하다. 구어 원천의 앞 어절별 분포를
웹 분포와 선형 보간한다.

    p(next | prev) = (1 - λ') * p_web + λ' * p_casual,   λ' = λ * n / (n + K)

n은 그 문맥에서 구어 원천이 관측한 횟수다. 관측이 적은 문맥에서 한두 번 나온 쌍이
구어 분포를 독차지해 맨 앞으로 오르지 않도록, 증거가 쌓일수록 λ에 다가가게 한다.

구어 원천:
  - ChatbotData.csv의 학습 분할(질문 해시 기준 80%, MIT). 나머지 20%는 평가 전용이다.
  - 손으로 쓴 nextword.txt 쌍 가운데 다음 어절이 웹 유니그램 상위 30,000위 안에 있는 것.
    이렇게 하면 띄어쓰기를 무시하고 붙여 쓴 구(예: "확인부탁드립니다")가 걸러진다.
구어 n-gram은 2회 이상 나온 것만 쓴다.

유니그램 가중치는 web_count + S * casual_count이고, 상위 150,000개를 남긴다.

--sweep 을 주면 --scale을 고정하고 λ·K 조합을 평가 분할로 채점해 표로 출력하고 파일은 쓰지 않는다.
--sweep 없이 실행하면 --lam, --k, --scale 값으로 병합 팩 TSV(unigram/bigram/trigram)를 --out-dir에 쓴다.
"""
from __future__ import annotations

import argparse
import csv
import hashlib
import importlib.util
import re
import sys
from collections import Counter, defaultdict
from pathlib import Path

HOLDOUT_MOD = 5
MIN_CASUAL_COUNT = 2
BIGRAM_TOP_K = 8
TRIGRAM_TOP_K = 5
TRIGRAM_CAP = 200_000
UNIGRAM_TOP_N = 150_000
CURATED_WEB_RANK_LIMIT = 30_000
WEIGHT_SCALE = 1_000_000
HANGUL_WORD = re.compile(r"[가-힣]{1,12}")


def _load_sentence_runs():
    builder = Path(__file__).resolve().parent / "build-ko-corpus-ngram.py"
    spec = importlib.util.spec_from_file_location("build_ko_corpus_ngram", builder)
    module = importlib.util.module_from_spec(spec)
    assert spec.loader is not None
    spec.loader.exec_module(module)
    return module.sentence_runs


sentence_runs = _load_sentence_runs()


def is_holdout(question: str) -> bool:
    digest = hashlib.blake2b(question.encode("utf-8"), digest_size=8).digest()
    return int.from_bytes(digest, "little") % HOLDOUT_MOD == 0


def chatbot_sentences(csv_path: Path, holdout: bool) -> list[list[str]]:
    runs: list[list[str]] = []
    with csv_path.open(encoding="utf-8", newline="") as f:
        for row in csv.DictReader(f):
            if is_holdout(row["Q"]) != holdout:
                continue
            for field in ("Q", "A"):
                runs.extend(sentence_runs(row[field]))
    return runs


def read_web_pack(pack: Path):
    uni: dict[str, int] = {}
    with (pack / "unigram.tsv").open(encoding="utf-8") as f:
        for line in f:
            if line.startswith("#"):
                continue
            parts = line.rstrip("\n").split("\t")
            uni[parts[0]] = int(parts[1])
    bi: dict[str, list[tuple[str, int]]] = defaultdict(list)
    with (pack / "bigram.tsv").open(encoding="utf-8") as f:
        for line in f:
            if line.startswith("#"):
                continue
            a, b, c = line.rstrip("\n").split("\t")[:3]
            bi[a].append((b, int(c)))
    tri: dict[tuple[str, str], list[tuple[str, int]]] = defaultdict(list)
    with (pack / "trigram.tsv").open(encoding="utf-8") as f:
        for line in f:
            if line.startswith("#"):
                continue
            a, b, c, n = line.rstrip("\n").split("\t")[:4]
            tri[(a, b)].append((c, int(n)))
    return uni, bi, tri


def casual_counts(train_runs: list[list[str]], nextword_path: Path, web_uni: dict[str, int]):
    uni: Counter = Counter()
    bi: dict[str, Counter] = defaultdict(Counter)
    tri: dict[tuple[str, str], Counter] = defaultdict(Counter)
    for run in train_runs:
        uni.update(run)
        for i in range(1, len(run)):
            bi[run[i - 1]][run[i]] += 1
        for i in range(2, len(run)):
            tri[(run[i - 2], run[i - 1])][run[i]] += 1
    known = set(sorted(web_uni, key=lambda w: -web_uni[w])[:CURATED_WEB_RANK_LIMIT])
    curated = 0
    for line in nextword_path.read_text(encoding="utf-8").splitlines():
        if not line.strip() or line.startswith("#"):
            continue
        prev, *nexts = line.split("\t")
        if not HANGUL_WORD.fullmatch(prev):
            continue
        for nxt in nexts:
            if HANGUL_WORD.fullmatch(nxt) and nxt in known:
                bi[prev][nxt] += MIN_CASUAL_COUNT
                curated += 1
    bi = {k: Counter({w: c for w, c in v.items() if c >= MIN_CASUAL_COUNT}) for k, v in bi.items()}
    tri = {k: Counter({w: c for w, c in v.items() if c >= MIN_CASUAL_COUNT}) for k, v in tri.items()}
    bi = {k: v for k, v in bi.items() if v}
    tri = {k: v for k, v in tri.items() if v}
    uni = Counter({w: c for w, c in uni.items() if c >= MIN_CASUAL_COUNT})
    return uni, bi, tri, curated


def interpolate(web: list[tuple[str, int]], casual: Counter | None, lam: float, k: float, top_k: int) -> list[tuple[str, float]]:
    scores: dict[str, float] = defaultdict(float)
    web_total = sum(c for _, c in web)
    casual_total = sum(casual.values()) if casual and lam > 0 else 0
    if not web_total:
        lam_eff = 1.0 if casual_total else 0.0
    else:
        lam_eff = lam * casual_total / (casual_total + k) if casual_total else 0.0
    if web_total:
        for w, c in web:
            scores[w] += (1.0 - lam_eff) * c / web_total
    if casual_total:
        for w, c in casual.items():
            scores[w] += lam_eff * c / casual_total
    return sorted(scores.items(), key=lambda t: (-t[1], t[0]))[:top_k]


def merge(web_uni, web_bi, web_tri, c_uni, c_bi, c_tri, lam: float, k: float, scale: float):
    uni_scores = dict(web_uni)
    for w, c in c_uni.items():
        uni_scores[w] = uni_scores.get(w, 0) + scale * c
    uni = sorted(uni_scores.items(), key=lambda t: (-t[1], t[0].encode("utf-8")))[:UNIGRAM_TOP_N]
    vocab = {w for w, _ in uni}

    bi: dict[str, list[tuple[str, float]]] = {}
    for prev in set(web_bi) | set(c_bi):
        if prev not in vocab:
            continue
        casual = Counter({w: c for w, c in c_bi.get(prev, Counter()).items() if w in vocab})
        merged = interpolate(web_bi.get(prev, []), casual, lam, k, BIGRAM_TOP_K)
        if merged:
            bi[prev] = merged

    tri_rows: list[tuple[tuple[str, str], str, float, float]] = []
    for pair in set(web_tri) | set(c_tri):
        if pair[0] not in vocab or pair[1] not in vocab:
            continue
        casual = Counter({w: c for w, c in c_tri.get(pair, Counter()).items() if w in vocab})
        web = web_tri.get(pair, [])
        for w, p in interpolate(web, casual, lam, k, TRIGRAM_TOP_K):
            support = sum(c for _, c in web) + sum(casual.values())
            tri_rows.append((pair, w, p, support * p))
    tri_rows.sort(key=lambda r: -r[3])
    tri: dict[tuple[str, str], list[tuple[str, float]]] = defaultdict(list)
    for pair, w, p, _ in tri_rows[:TRIGRAM_CAP]:
        tri[pair].append((w, p))
    for pair in tri:
        tri[pair].sort(key=lambda t: (-t[1], t[0]))
    return uni, bi, tri


def evaluate(runs: list[list[str]], uni, bi, tri) -> dict[str, float]:
    ranked = [w for w, _ in uni]
    vocab = set(ranked)
    by_prefix: dict[tuple[int, str], list[str]] = defaultdict(list)
    for w in ranked:
        for n in (1, 2):
            if len(w) >= n and len(by_prefix[(n, w[:n])]) < 4:
                by_prefix[(n, w[:n])].append(w)
    words = sum(len(r) for r in runs)
    covered = sum(1 for r in runs for w in r if w in vocab)
    pref_total = pref1 = pref2 = 0
    pairs = provided = 0
    hits = {1: 0, 3: 0, 5: 0}
    for run in runs:
        for w in run:
            if len(w) >= 2:
                pref_total += 1
                pref1 += w in by_prefix.get((1, w[:1]), [])
                pref2 += w in by_prefix.get((2, w[:2]), [])
        for i in range(1, len(run)):
            pairs += 1
            cands: list[str] = []
            if i >= 2:
                cands = [w for w, _ in tri.get((run[i - 2], run[i - 1]), [])]
            for w, _ in bi.get(run[i - 1], []):
                if w not in cands:
                    cands.append(w)
            if cands:
                provided += 1
            for k in hits:
                if run[i] in cands[:k]:
                    hits[k] += 1
    return {
        "coverage": covered / words if words else 0.0,
        "prefix1": pref1 / pref_total if pref_total else 0.0,
        "prefix2": pref2 / pref_total if pref_total else 0.0,
        "provided": provided / pairs if pairs else 0.0,
        "hit1": hits[1] / pairs if pairs else 0.0,
        "hit3": hits[3] / pairs if pairs else 0.0,
        "hit5": hits[5] / pairs if pairs else 0.0,
    }


def web_test_runs(shard: Path, target_words: int) -> list[list[str]]:
    import pyarrow.parquet as pq

    runs: list[list[str]] = []
    total = 0
    for batch in pq.ParquetFile(shard).iter_batches(batch_size=1024, columns=["text"]):
        for text in batch.column("text").to_pylist():
            for run in sentence_runs(text):
                runs.append(run)
                total += len(run)
            if total >= target_words:
                return runs
    return runs


def write_pack(out: Path, uni, bi, tri) -> None:
    out.mkdir(parents=True, exist_ok=True)
    with (out / "unigram.tsv").open("w", encoding="utf-8", newline="\n") as f:
        for w, c in uni:
            f.write(f"{w}\t{max(1, round(c))}\n")
    with (out / "bigram.tsv").open("w", encoding="utf-8", newline="\n") as f:
        for prev in sorted(bi, key=lambda s: s.encode("utf-8")):
            for w, p in bi[prev]:
                f.write(f"{prev}\t{w}\t{max(1, round(p * WEIGHT_SCALE))}\n")
    with (out / "trigram.tsv").open("w", encoding="utf-8", newline="\n") as f:
        for pair in sorted(tri, key=lambda t: (t[0].encode("utf-8"), t[1].encode("utf-8"))):
            for w, p in tri[pair]:
                f.write(f"{pair[0]}\t{pair[1]}\t{w}\t{max(1, round(p * WEIGHT_SCALE))}\n")


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--web-pack", type=Path, required=True)
    parser.add_argument("--chatbot-csv", type=Path, required=True)
    parser.add_argument("--nextword", type=Path, default=Path("plugin/hangul/src/main/cpp/fcitx5-hangul/data/nextword.txt"))
    parser.add_argument("--sentence-pack", type=Path, default=Path("app/src/main/assets/sentence-packs/ko-basic-v1.txt"))
    parser.add_argument("--test-shard", type=Path)
    parser.add_argument("--web-test-words", type=int, default=300_000)
    parser.add_argument("--sweep", action="store_true")
    parser.add_argument("--lam", type=float, default=0.5)
    parser.add_argument("--k", type=float, default=0.0)
    parser.add_argument("--scale", type=float, default=0.0)
    parser.add_argument("--out-dir", type=Path)
    args = parser.parse_args()

    web_uni, web_bi, web_tri = read_web_pack(args.web_pack)
    train = chatbot_sentences(args.chatbot_csv, holdout=False)
    holdout = chatbot_sentences(args.chatbot_csv, holdout=True)
    c_uni, c_bi, c_tri, curated = casual_counts(train, args.nextword, web_uni)
    print(
        f"casual: train_sentences={len(train)} holdout_sentences={len(holdout)} "
        f"uni={len(c_uni)} bi_prev={len(c_bi)} bi_pairs={sum(len(v) for v in c_bi.values())} "
        f"tri_pairs={len(c_tri)} curated_pairs={curated}",
        file=sys.stderr,
    )

    if not args.sweep:
        if args.out_dir is None:
            parser.error("--out-dir is required without --sweep")
        uni, bi, tri = merge(web_uni, web_bi, web_tri, c_uni, c_bi, c_tri, args.lam, args.k, args.scale)
        write_pack(args.out_dir, uni, bi, tri)
        print(f"wrote {args.out_dir}: uni={len(uni)} bi_prev={len(bi)} tri_pairs={len(tri)} "
              f"lam={args.lam} k={args.k} scale={args.scale}")
        return

    sets = {"chat_holdout": holdout}
    sets["sentence_pack"] = [r for line in args.sentence_pack.read_text(encoding="utf-8").splitlines() for r in sentence_runs(line)]
    if args.test_shard:
        sets["web_test"] = web_test_runs(args.test_shard, args.web_test_words)
    metrics = ("coverage", "prefix1", "prefix2", "provided", "hit1", "hit3", "hit5")
    print("lam\tk\tset\t" + "\t".join(metrics) + f"\t(scale={args.scale:g})")
    for lam in (0.5, 0.7, 0.9):
        for k in (0.0, 5.0, 10.0, 20.0, 40.0):
            uni, bi, tri = merge(web_uni, web_bi, web_tri, c_uni, c_bi, c_tri, lam, k, args.scale)
            for name, runs in sets.items():
                m = evaluate(runs, uni, bi, tri)
                print(f"{lam}\t{k:g}\t{name}\t" + "\t".join(f"{m[key] * 100:.2f}" for key in metrics), flush=True)


if __name__ == "__main__":
    main()
