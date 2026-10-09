#!/usr/bin/env python3
# Source: HuggingFaceFW/fineweb-2 (config kor_Hang), License: ODC-By 1.0, https://huggingface.co/datasets/HuggingFaceFW/fineweb-2
#         + songys/Chatbot_data (MIT)
"""문장 시작(BOS) 분포가 문장 첫머리 후보를 얼마나 좋게 하는지 잰다. 평가는 문장의 첫 어절과 둘째
어절만 본다. 평가 문장의 BOS 판정은 build-ko-sentence-start.py와 같은 규칙이다(첫 원시 토큰이 유효
어절일 때만 표본이고, 둘째 어절은 같은 run의 다음 어절).

평가 세트
  E1  FineWeb-2 kor_Hang test 샤드 앞 --e1-target-words 어절(문장 중복 제거·문서 필터 없음)
  E2  ChatbotData.csv 평가 분할(질문 blake2b 해시 mod 5 == 0)의 Q·A
  E3  app/src/main/assets/sentence-packs/ko-basic-v1.txt

지표
  a. 무입력 hit@1, hit@4  BOS 분포 상위 k에 실제 첫 어절이 있는가(현행은 후보 없음 = 0)
  b. 접두 완성 top4  접두 = 첫 음절의 초성 자모 / 첫 음절 / 첫 두 음절(2음절 이상 어절만)
       global     ko_base_vocab 전역 빈도순 startsWith(자모 접두는 첫 음절 초성이 같은 어절)
       bos_first  접두에 맞는 BOS 항목을 BOS 확률순으로 먼저, 남는 자리는 global 순
       interp_bN  접두 후보 집합 안에서 정규화한 p = b * p_bos + (1 - b) * p_global (b = 0.3/0.5/0.7)
  c. 둘째 어절 hit@1, hit@5
       bigram_only  현 ko-ngram.bin의 w1 bigram
       bos_trigram  (<s>, w1) 후보를 먼저, 남는 자리는 bigram
"""

from __future__ import annotations

import argparse
import heapq
import importlib.util
import json
import time
from collections import defaultdict
from pathlib import Path

import pyarrow.parquet as pq

SCRIPT_DIR = Path(__file__).resolve().parent
REPO_ROOT = SCRIPT_DIR.parent

CHOSEONG = "ㄱㄲㄴㄷㄸㄹㅁㅂㅃㅅㅆㅇㅈㅉㅊㅋㅌㅍㅎ"
PREFIX_KINDS = ("jamo", "syl1", "syl2")
PREFIX_LABELS = {"jamo": "초성 1개", "syl1": "첫 음절", "syl2": "첫 두 음절"}
BETAS = (0.3, 0.5, 0.7)
SAMPLE_JAMO = ("ㄱ", "ㅇ", "ㄴ")


def _load_build_module():
    path = SCRIPT_DIR / "build-ko-sentence-start.py"
    spec = importlib.util.spec_from_file_location("build_ko_sentence_start", path)
    module = importlib.util.module_from_spec(spec)
    assert spec.loader is not None
    spec.loader.exec_module(module)
    return module


build = _load_build_module()
builder = build.builder
merger = build.merger
packer = build.packer


def choseong_of(syllable: str) -> str:
    return CHOSEONG[(ord(syllable) - 0xAC00) // 588]


def prefix_key(word: str, kind: str) -> str | None:
    if kind == "jamo":
        return choseong_of(word[0])
    if kind == "syl1":
        return word[0]
    return word[:2] if len(word) >= 2 else None


def load_e1(shard: Path, target_words: int) -> list[tuple[str, str | None]]:
    starts: list[tuple[str, str | None]] = []
    total = 0
    for text in builder.iter_shard_texts(shard):
        for sentence, runs in builder.iter_sentences(text):
            total += sum(len(r) for r in runs)
            start = build.sentence_start(sentence, runs)
            if start is not None:
                starts.append(start)
        if total >= target_words:
            break
    return starts


def load_e2(csv_path: Path) -> list[tuple[str, str | None]]:
    import csv

    starts: list[tuple[str, str | None]] = []
    with csv_path.open(encoding="utf-8", newline="") as f:
        for row in csv.DictReader(f):
            if not merger.is_holdout(row["Q"]):
                continue
            for field in ("Q", "A"):
                starts.extend(build.text_sentence_starts(row[field]))
    return starts


def load_e3(path: Path) -> list[tuple[str, str | None]]:
    starts: list[tuple[str, str | None]] = []
    for line in path.read_text(encoding="utf-8").splitlines():
        if line.strip():
            starts.extend(build.text_sentence_starts(line))
    return starts


class PrefixCompleter:
    """Top-4 prefix completion over the global vocabulary, for every method, cached per prefix."""

    def __init__(self, vocab_rows: list[tuple[str, int]]) -> None:
        self.words = [w for w, _ in vocab_rows]
        self.counts = [c for _, c in vocab_rows]
        self.rank = {w: i for i, w in enumerate(self.words)}
        self.by_key: dict[tuple[str, str], list[int]] = {}
        for i, w in enumerate(self.words):
            for kind in PREFIX_KINDS:
                key = prefix_key(w, kind)
                if key is not None:
                    self.by_key.setdefault((kind, key), []).append(i)

    def set_bos(self, bos_rows: list[tuple[str, float]]) -> None:
        self.bos_rows = bos_rows
        self.bos_prob = dict(bos_rows)
        self.bos_by_key: dict[tuple[str, str], list[str]] = defaultdict(list)
        for w, _p in bos_rows:
            if w not in self.rank:
                continue
            for kind in PREFIX_KINDS:
                key = prefix_key(w, kind)
                if key is not None:
                    self.bos_by_key[(kind, key)].append(w)
        self.cache: dict[tuple, list[str]] = {}

    def global_top(self, kind: str, key: str) -> list[str]:
        return [self.words[i] for i in self.by_key.get((kind, key), [])[:4]]

    def bos_first(self, kind: str, key: str) -> list[str]:
        out = self.bos_by_key.get((kind, key), [])[:4]
        for i in self.by_key.get((kind, key), []):
            if len(out) >= 4:
                break
            if self.words[i] not in out:
                out.append(self.words[i])
        return out

    def interp(self, kind: str, key: str, beta: float) -> list[str]:
        cand = self.by_key.get((kind, key), [])
        if not cand:
            return []
        g_total = sum(self.counts[i] for i in cand)
        b_total = sum(self.bos_prob.get(self.words[i], 0.0) for i in cand)
        scored = []
        for i in cand:
            pg = self.counts[i] / g_total
            pb = self.bos_prob.get(self.words[i], 0.0) / b_total if b_total else 0.0
            scored.append((-(beta * pb + (1.0 - beta) * pg), i))
        return [self.words[i] for _s, i in heapq.nsmallest(4, scored)]

    def top4(self, method: str, kind: str, key: str) -> list[str]:
        cache_key = (method, kind, key)
        hit = self.cache.get(cache_key)
        if hit is None:
            if method == "global":
                hit = self.global_top(kind, key)
            elif method == "bos_first":
                hit = self.bos_first(kind, key)
            else:
                hit = self.interp(kind, key, float(method.rsplit("_b", 1)[1]))
            self.cache[cache_key] = hit
        return hit


def read_bos_unigram(path: Path) -> list[tuple[str, float]]:
    rows = []
    for line in path.read_text(encoding="utf-8").splitlines():
        w, _count, prob = line.split("\t")
        rows.append((w, float(prob)))
    return rows


def read_bos_bigram(path: Path) -> dict[str, list[str]]:
    grouped: dict[str, list[tuple[str, int]]] = defaultdict(list)
    for line in path.read_text(encoding="utf-8").splitlines():
        w1, w2, count = line.split("\t")
        grouped[w1].append((w2, int(count)))
    return {w1: [w for w, _ in sorted(c, key=lambda t: (-t[1], t[0].encode("utf-8")))] for w1, c in grouped.items()}


def fill(first: list[str], rest: list[str], k: int) -> list[str]:
    out = list(first[:k])
    for w in rest:
        if len(out) >= k:
            break
        if w not in out:
            out.append(w)
    return out


def evaluate_set(starts, completer: PrefixCompleter, bos_rows, bos_bigram, reader, bigram_cache) -> dict:
    total = len(starts)
    ranked_bos = [w for w, _ in bos_rows]
    result: dict = {"sentences": total, "first_in_vocab": sum(1 for w1, _ in starts if w1 in completer.rank) / total}
    bos_set = set(ranked_bos)
    result["first_in_bos_list"] = sum(1 for w1, _ in starts if w1 in bos_set) / total
    result["no_input"] = {}
    for k in (1, 4):
        top = set(ranked_bos[:k])
        result["no_input"][f"hit@{k}"] = sum(1 for w1, _ in starts if w1 in top) / total

    methods = ["global", "bos_first"] + [f"interp_b{b:g}" for b in BETAS]
    prefix: dict = {}
    for kind in PREFIX_KINDS:
        rows = [w1 for w1, _ in starts if prefix_key(w1, kind) is not None]
        entry = {"n": len(rows)}
        for method in methods:
            hits = sum(1 for w1 in rows if w1 in completer.top4(method, kind, prefix_key(w1, kind)))
            entry[method] = hits / len(rows) if rows else 0.0
        prefix[kind] = entry
    result["prefix_top4"] = prefix

    pairs = [(w1, w2) for w1, w2 in starts if w2 is not None]
    second = {"n": len(pairs)}
    for name in ("bigram_only", "bos_trigram"):
        for k in (1, 5):
            hits = 0
            for w1, w2 in pairs:
                bi = bigram_cache(w1)
                cands = bi if name == "bigram_only" else fill(bos_bigram.get(w1, []), bi, k)
                hits += w2 in cands[:k]
            second[f"{name}_hit@{k}"] = hits / len(pairs) if pairs else 0.0
    result["second_word"] = second
    return result


def pct(x: float) -> str:
    return f"{x * 100:.2f}"


def markdown_tables(results: dict, lams: list[float]) -> str:
    lines: list[str] = []
    for set_name, per_lam in results.items():
        base = per_lam[f"{lams[0]:g}"]
        lines.append(f"### {set_name} (BOS 문장 {base['sentences']}개, 첫 어절이 어휘 안 {pct(base['first_in_vocab'])}%, 둘째 어절 쌍 {base['second_word']['n']}개)")
        lines.append("")
        lines.append("무입력 후보 (현행 = 후보 없음 = 0)")
        lines.append("")
        lines.append("| λ | hit@1 | hit@4 | 첫 어절이 BOS 목록 안 |")
        lines.append("|---|---|---|---|")
        lines.append("| 현행(없음) | 0.00 | 0.00 | - |")
        for lam in lams:
            r = per_lam[f"{lam:g}"]
            lines.append(f"| {lam:g} | {pct(r['no_input']['hit@1'])} | {pct(r['no_input']['hit@4'])} | {pct(r['first_in_bos_list'])} |")
        lines.append("")
        methods = ["global", "bos_first"] + [f"interp_b{b:g}" for b in BETAS]
        for kind in PREFIX_KINDS:
            lines.append(f"접두 완성 top4: {PREFIX_LABELS[kind]} (n={base['prefix_top4'][kind]['n']})")
            lines.append("")
            lines.append("| λ | " + " | ".join(methods) + " |")
            lines.append("|---|" + "---|" * len(methods))
            for lam in lams:
                p = per_lam[f"{lam:g}"]["prefix_top4"][kind]
                lines.append(f"| {lam:g} | " + " | ".join(pct(p[m]) for m in methods) + " |")
            lines.append("")
        lines.append("둘째 어절")
        lines.append("")
        lines.append("| λ | bigram_only hit@1 | bos_trigram hit@1 | bigram_only hit@5 | bos_trigram hit@5 |")
        lines.append("|---|---|---|---|---|")
        for lam in lams:
            s = per_lam[f"{lam:g}"]["second_word"]
            lines.append(
                f"| {lam:g} | {pct(s['bigram_only_hit@1'])} | {pct(s['bos_trigram_hit@1'])} | "
                f"{pct(s['bigram_only_hit@5'])} | {pct(s['bos_trigram_hit@5'])} |"
            )
        lines.append("")
    return "\n".join(lines)


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--pack-root", type=Path, required=True, help="build-ko-sentence-start.py의 --out-dir (lam_<값>/ 들이 있는 곳)")
    parser.add_argument("--lams", type=float, nargs="+", default=[0.0, 0.3, 0.5, 0.7])
    parser.add_argument("--test-shard", type=Path, required=True)
    parser.add_argument("--chatbot-csv", type=Path, required=True)
    parser.add_argument("--ngram-bin", type=Path, default=REPO_ROOT / "app/src/main/assets/korean/ko-ngram.bin")
    parser.add_argument("--e1-target-words", type=int, default=1_000_000)
    parser.add_argument("--out", type=Path, required=True, help="결과 JSON 경로")
    args = parser.parse_args()

    t0 = time.time()
    completer = PrefixCompleter(build.load_vocab_order())
    reader = packer.NgramBinReader(args.ngram_bin.read_bytes())
    bigram_memo: dict[str, list[str]] = {}

    def bigram_cache(w1: str) -> list[str]:
        cached = bigram_memo.get(w1)
        if cached is None:
            cached = [w for w, _ in reader.bigram_next(w1)]
            bigram_memo[w1] = cached
        return cached

    sets = {
        "E1_fineweb_test": load_e1(args.test_shard, args.e1_target_words),
        "E2_chatbot_holdout": load_e2(args.chatbot_csv),
        "E3_sentence_pack": load_e3(REPO_ROOT / "app/src/main/assets/sentence-packs/ko-basic-v1.txt"),
    }
    print(f"평가 세트: " + ", ".join(f"{k}={len(v)}문장" for k, v in sets.items()) + f" ({time.time() - t0:.1f}s)")

    results: dict = {name: {} for name in sets}
    samples: dict = {}
    for lam in args.lams:
        lam_key = f"{lam:g}"
        lam_dir = args.pack_root / build.lam_dir_name(lam)
        bos_rows = read_bos_unigram(lam_dir / "bos_unigram.tsv")
        bos_bigram = read_bos_bigram(lam_dir / "bos_bigram.tsv")
        completer.set_bos(bos_rows)
        for name, starts in sets.items():
            results[name][lam_key] = evaluate_set(starts, completer, bos_rows, bos_bigram, reader, bigram_cache)
        samples[lam_key] = {
            "bos_top30": [w for w, _ in bos_rows[:30]],
            "bos_first_top4": {j: completer.top4("bos_first", "jamo", j) for j in SAMPLE_JAMO},
            "global_top4": {j: completer.top4("global", "jamo", j) for j in SAMPLE_JAMO},
        }
        print(f"λ={lam_key} 평가 완료 ({time.time() - t0:.1f}s)")

    markdown = markdown_tables(results, args.lams)
    args.out.parent.mkdir(parents=True, exist_ok=True)
    args.out.write_text(
        json.dumps({"results": results, "samples": samples, "markdown": markdown}, ensure_ascii=False, indent=2),
        encoding="utf-8",
    )
    print(markdown)
    for lam in args.lams:
        s = samples[f"{lam:g}"]
        print(f"## λ={lam:g} BOS 상위 30: " + " ".join(s["bos_top30"]))
        for j in SAMPLE_JAMO:
            print(f"   초성 {j}: bos_first {s['bos_first_top4'][j]} / global {s['global_top4'][j]}")
    print(f"결과 JSON: {args.out} ({time.time() - t0:.1f}s)")


if __name__ == "__main__":
    main()
