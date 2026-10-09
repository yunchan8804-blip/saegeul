#!/usr/bin/env python3
# Source: HuggingFaceFW/fineweb-2 (config kor_Hang), License: ODC-By 1.0, https://huggingface.co/datasets/HuggingFaceFW/fineweb-2
#         + songys/Chatbot_data (MIT) casual layer merged by scripts/merge-ko-casual-layer.py
"""Convert the FineWeb-2 Korean eojeol n-gram pack (unigram/bigram/trigram
TSVs built by scripts/build-ko-corpus-ngram.py) into the two app assets:

  - app/src/main/assets/ko_base_vocab.tsv
      one comment header line + `word<TAB>count`, 150,000 rows, count
      descending, ties broken by the word's UTF-8 byte order.

  - app/src/main/assets/korean/ko-ngram.bin
      a fixed binary layout (see the KONGRAM1 header) holding a sorted
      vocabulary of every word appearing in a surviving bigram/trigram
      entry, plus CSR-encoded bigram and trigram next-word tables.

Bigram/trigram entries whose *next* word is in NEXT_EXCLUDE are dropped
before the vocabulary and CSR tables are built; NEXT_EXCLUDE never applies
to a word appearing as a bigram/trigram prefix (prev, or trigram a/b).

Run with --verify to re-read the written .bin from disk, check header/offset
consistency, cross-check entry counts against the source TSVs, and print a
handful of bigram/trigram lookups.

A run that would overwrite an assets ko-ngram.bin holding `<s>` rows with a bin
that has none is refused before any asset is written, unless
--allow-drop-sentence-start is given (the `<s>` rows are then restored by the
--add-sentence-start step below).

Run with --add-sentence-start <dir> (a lam_<값> directory from
scripts/build-ko-sentence-start.py holding bos_unigram.tsv / bos_bigram.tsv)
to experiment with sentence-start data: every bigram/trigram row of the
existing assets ko-ngram.bin is kept, the reserved token `<s>` is added as a
bigram prefix (bos_unigram rows) and as the first word of trigram pairs
((<s>, w1) -> w2 from bos_bigram rows), and the result is written only to
--sentence-start-out (default build/ko-sentence-start/ko-ngram.bin). The app
assets are never touched in this mode. The format, header and NEXT_EXCLUDE
rule are unchanged. With --sentence-start-no-trigram the bos_bigram.tsv rows
are left out, so only the `<s>` bigram row is added. An assets file that
already holds `<s>` is refused as the base, since the rows would be added twice.
"""

from __future__ import annotations

import argparse
import bisect
import hashlib
import struct
import sys
from pathlib import Path

NEXT_EXCLUDE = {"은", "는", "을", "를", "의", "에", "년", "월"}
MAGIC = b"KONGRAM1"
VERSION = 1
HEADER_SIZE = 28
MAX_BIN_BYTES = 32 * 1024 * 1024
UNIGRAM_ROWS = 150_000

BIGRAM_LOOKUP_SAMPLES = ["퇴근하고", "그럼", "밥", "주말에", "오늘", "감사합니다"]
SENTENCE_START_TOKEN = "<s>"
DEFAULT_SENTENCE_START_OUT = Path("build/ko-sentence-start/ko-ngram.bin")
SENTENCE_START_DROPPED_WARNING = (
    f"{SENTENCE_START_TOKEN} 행이 빠졌으니 docs/korean-input/korean-smart-input-ssot.md의 "
    "--add-sentence-start 단계가 필요함"
)


def sha256_of(path: Path) -> str:
    h = hashlib.sha256()
    with path.open("rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def read_unigram(path: Path) -> list[tuple[str, int, int]]:
    rows: list[tuple[str, int, int]] = []
    with path.open("r", encoding="utf-8") as f:
        for lineno, line in enumerate(f, start=1):
            line = line.rstrip("\n")
            if not line:
                continue
            parts = line.split("\t")
            if len(parts) not in (2, 3):
                raise ValueError(f"unigram.tsv:{lineno} 형식 오류: {line!r}")
            doc_freq = int(parts[2]) if len(parts) == 3 else 0
            rows.append((parts[0], int(parts[1]), doc_freq))
    return rows


def read_bigram(path: Path) -> list[tuple[str, str, int]]:
    rows: list[tuple[str, str, int]] = []
    with path.open("r", encoding="utf-8") as f:
        for lineno, line in enumerate(f, start=1):
            line = line.rstrip("\n")
            if not line:
                continue
            parts = line.split("\t")
            if len(parts) != 3:
                raise ValueError(f"bigram.tsv:{lineno} 형식 오류: {line!r}")
            prev, nxt, count_s = parts
            rows.append((prev, nxt, int(count_s)))
    return rows


def read_trigram(path: Path) -> list[tuple[str, str, str, int]]:
    rows: list[tuple[str, str, str, int]] = []
    with path.open("r", encoding="utf-8") as f:
        for lineno, line in enumerate(f, start=1):
            line = line.rstrip("\n")
            if not line:
                continue
            parts = line.split("\t")
            if len(parts) != 4:
                raise ValueError(f"trigram.tsv:{lineno} 형식 오류: {line!r}")
            a, b, nxt, count_s = parts
            rows.append((a, b, nxt, int(count_s)))
    return rows


def write_vocab_tsv(unigram_rows: list[tuple[str, int, int]], out_path: Path) -> int:
    if len(unigram_rows) != UNIGRAM_ROWS:
        raise ValueError(f"unigram.tsv 행 수가 {UNIGRAM_ROWS}이 아님: {len(unigram_rows)}")
    ordered = sorted(unigram_rows, key=lambda r: (-r[1], r[0].encode("utf-8")))
    header = (
        "# Source: FineWeb-2 (HuggingFaceFW/fineweb-2, kor_Hang), License: ODC-By 1.0, "
        "https://huggingface.co/datasets/HuggingFaceFW/fineweb-2; "
        "songys/Chatbot_data (MIT), https://github.com/songys/Chatbot_data"
    )
    lines = [header]
    lines.extend(f"{word}\t{count}" for word, count, _doc_freq in ordered)
    data = ("\n".join(lines) + "\n").encode("utf-8")
    out_path.parent.mkdir(parents=True, exist_ok=True)
    out_path.write_bytes(data)
    return len(data)


def build_ngram_bin(
    bigram_rows: list[tuple[str, str, int]],
    trigram_rows: list[tuple[str, str, str, int]],
) -> tuple[bytes, list[str], dict]:
    bi_filtered = [(p, n, c) for p, n, c in bigram_rows if n not in NEXT_EXCLUDE]
    tri_filtered = [(a, b, n, c) for a, b, n, c in trigram_rows if n not in NEXT_EXCLUDE]

    vocab_set: set[str] = set()
    for p, n, _c in bi_filtered:
        vocab_set.add(p)
        vocab_set.add(n)
    for a, b, n, _c in tri_filtered:
        vocab_set.add(a)
        vocab_set.add(b)
        vocab_set.add(n)
    vocab = sorted(vocab_set, key=lambda w: w.encode("utf-8"))
    word_id = {w: i for i, w in enumerate(vocab)}
    v = len(vocab)

    bi_groups: dict[int, list[tuple[int, int]]] = {}
    for p, n, c in bi_filtered:
        bi_groups.setdefault(word_id[p], []).append((word_id[n], c))
    for entries in bi_groups.values():
        entries.sort(key=lambda t: (-t[1], t[0]))

    bi_start = [0] * (v + 1)
    bi_next: list[int] = []
    bi_count: list[int] = []
    for i in range(v):
        bi_start[i] = len(bi_next)
        for nid, c in bi_groups.get(i, []):
            bi_next.append(nid)
            bi_count.append(c)
    bi_start[v] = len(bi_next)
    b_total = len(bi_next)

    tri_groups: dict[tuple[int, int], list[tuple[int, int]]] = {}
    for a, b, n, c in tri_filtered:
        tri_groups.setdefault((word_id[a], word_id[b]), []).append((word_id[n], c))
    pairs = sorted(tri_groups.keys())

    tri_a: list[int] = []
    tri_b: list[int] = []
    tri_start = [0]
    tri_next: list[int] = []
    tri_count: list[int] = []
    for pair in pairs:
        entries = tri_groups[pair]
        entries.sort(key=lambda t: (-t[1], t[0]))
        tri_a.append(pair[0])
        tri_b.append(pair[1])
        for nid, c in entries:
            tri_next.append(nid)
            tri_count.append(c)
        tri_start.append(len(tri_next))
    p_total = len(pairs)
    t_total = len(tri_next)

    word_offsets = [0] * (v + 1)
    blob_parts: list[bytes] = []
    offset = 0
    for i, w in enumerate(vocab):
        wb = w.encode("utf-8")
        word_offsets[i] = offset
        blob_parts.append(wb)
        offset += len(wb)
    word_offsets[v] = offset
    blob = b"".join(blob_parts)
    pad = (-len(blob)) % 4
    blob_padded = blob + b"\x00" * pad

    header = MAGIC + struct.pack("<IIIII", VERSION, v, b_total, p_total, t_total)
    if len(header) != HEADER_SIZE:
        raise AssertionError(f"header size mismatch: {len(header)}")

    parts = [
        header,
        struct.pack(f"<{v + 1}I", *word_offsets),
        blob_padded,
        struct.pack(f"<{v + 1}I", *bi_start),
        struct.pack(f"<{b_total}I", *bi_next),
        struct.pack(f"<{b_total}I", *bi_count),
        struct.pack(f"<{p_total}I", *tri_a),
        struct.pack(f"<{p_total}I", *tri_b),
        struct.pack(f"<{p_total + 1}I", *tri_start),
        struct.pack(f"<{t_total}I", *tri_next),
        struct.pack(f"<{t_total}I", *tri_count),
    ]
    data = b"".join(parts)

    stats = {
        "V": v,
        "B": b_total,
        "P": p_total,
        "T": t_total,
        "bigram_source_rows": len(bigram_rows),
        "bigram_dropped_by_exclude": len(bigram_rows) - len(bi_filtered),
        "trigram_source_rows": len(trigram_rows),
        "trigram_dropped_by_exclude": len(trigram_rows) - len(tri_filtered),
    }
    return data, vocab, stats


class NgramBinReader:
    def __init__(self, data: bytes) -> None:
        self.data = data
        magic = data[0:8]
        if magic != MAGIC:
            raise ValueError(f"magic 불일치: {magic!r}")
        version, v, b_total, p_total, t_total = struct.unpack_from("<IIIII", data, 8)
        if version != VERSION:
            raise ValueError(f"version 불일치: {version}")
        self.v = v
        self.b_total = b_total
        self.p_total = p_total
        self.t_total = t_total

        off = HEADER_SIZE
        self.word_offsets = list(struct.unpack_from(f"<{v + 1}I", data, off))
        off += 4 * (v + 1)
        blob_len = self.word_offsets[v]
        self.blob_start = off
        self.blob = data[off:off + blob_len]
        off += blob_len
        pad = (-blob_len) % 4
        off += pad

        self.bi_start = list(struct.unpack_from(f"<{v + 1}I", data, off))
        off += 4 * (v + 1)
        self.bi_next = list(struct.unpack_from(f"<{b_total}I", data, off))
        off += 4 * b_total
        self.bi_count = list(struct.unpack_from(f"<{b_total}I", data, off))
        off += 4 * b_total

        self.tri_a = list(struct.unpack_from(f"<{p_total}I", data, off))
        off += 4 * p_total
        self.tri_b = list(struct.unpack_from(f"<{p_total}I", data, off))
        off += 4 * p_total
        self.tri_start = list(struct.unpack_from(f"<{p_total + 1}I", data, off))
        off += 4 * (p_total + 1)
        self.tri_next = list(struct.unpack_from(f"<{t_total}I", data, off))
        off += 4 * t_total
        self.tri_count = list(struct.unpack_from(f"<{t_total}I", data, off))
        off += 4 * t_total

        self.end_offset = off
        if off != len(data):
            raise ValueError(f"파일 끝 오프셋 불일치: 계산={off} 실제={len(data)}")

    def word(self, wid: int) -> str:
        start = self.word_offsets[wid]
        end = self.word_offsets[wid + 1]
        return self.blob[start:end].decode("utf-8")

    def word_id(self, word: str) -> int | None:
        wb = word.encode("utf-8")
        lo, hi = 0, self.v
        while lo < hi:
            mid = (lo + hi) // 2
            mw = self.blob[self.word_offsets[mid]:self.word_offsets[mid + 1]]
            if mw < wb:
                lo = mid + 1
            else:
                hi = mid
        if lo < self.v and self.blob[self.word_offsets[lo]:self.word_offsets[lo + 1]] == wb:
            return lo
        return None

    def bigram_next(self, prev: str) -> list[tuple[str, int]]:
        wid = self.word_id(prev)
        if wid is None:
            return []
        start, end = self.bi_start[wid], self.bi_start[wid + 1]
        return [(self.word(self.bi_next[i]), self.bi_count[i]) for i in range(start, end)]


def sentence_start_row_count(reader: NgramBinReader) -> int:
    wid = reader.word_id(SENTENCE_START_TOKEN)
    if wid is None:
        return 0
    return reader.bi_start[wid + 1] - reader.bi_start[wid]


def trigram_next(reader: NgramBinReader, a: str, b: str) -> list[tuple[str, int]]:
    aid, bid = reader.word_id(a), reader.word_id(b)
    if aid is None or bid is None:
        return []
    idx = bisect.bisect_left(list(zip(reader.tri_a, reader.tri_b)), (aid, bid))
    if idx >= reader.p_total or (reader.tri_a[idx], reader.tri_b[idx]) != (aid, bid):
        return []
    start, end = reader.tri_start[idx], reader.tri_start[idx + 1]
    return [(reader.word(reader.tri_next[i]), reader.tri_count[i]) for i in range(start, end)]


def read_bin_rows(reader: NgramBinReader):
    """Every bigram/trigram row of a KONGRAM1 file, in stored order."""
    bigrams: list[tuple[str, str, int]] = []
    for wid in range(reader.v):
        prev = reader.word(wid)
        for i in range(reader.bi_start[wid], reader.bi_start[wid + 1]):
            bigrams.append((prev, reader.word(reader.bi_next[i]), reader.bi_count[i]))
    trigrams: list[tuple[str, str, str, int]] = []
    for pair in range(reader.p_total):
        a, b = reader.word(reader.tri_a[pair]), reader.word(reader.tri_b[pair])
        for i in range(reader.tri_start[pair], reader.tri_start[pair + 1]):
            trigrams.append((a, b, reader.word(reader.tri_next[i]), reader.tri_count[i]))
    return bigrams, trigrams


def read_sentence_start_rows(start_dir: Path):
    bos_bigram: list[tuple[str, str, int]] = []
    with (start_dir / "bos_unigram.tsv").open("r", encoding="utf-8") as f:
        for lineno, line in enumerate(f, start=1):
            line = line.rstrip("\n")
            if not line:
                continue
            parts = line.split("\t")
            if len(parts) != 3:
                raise ValueError(f"bos_unigram.tsv:{lineno} 형식 오류: {line!r}")
            bos_bigram.append((SENTENCE_START_TOKEN, parts[0], int(parts[1])))
    bos_trigram: list[tuple[str, str, str, int]] = []
    with (start_dir / "bos_bigram.tsv").open("r", encoding="utf-8") as f:
        for lineno, line in enumerate(f, start=1):
            line = line.rstrip("\n")
            if not line:
                continue
            parts = line.split("\t")
            if len(parts) != 3:
                raise ValueError(f"bos_bigram.tsv:{lineno} 형식 오류: {line!r}")
            bos_trigram.append((SENTENCE_START_TOKEN, parts[0], parts[1], int(parts[2])))
    return bos_bigram, bos_trigram


def _group_bigrams(rows):
    grouped: dict[str, list[tuple[str, int]]] = {}
    for p, n, c in rows:
        grouped.setdefault(p, []).append((n, c))
    return grouped


def _group_trigrams(rows):
    grouped: dict[tuple[str, str], list[tuple[str, int]]] = {}
    for a, b, n, c in rows:
        grouped.setdefault((a, b), []).append((n, c))
    return grouped


def add_sentence_start(base_bin: Path, start_dir: Path, out_path: Path, include_trigram: bool = True) -> None:
    if out_path.resolve() == base_bin.resolve():
        raise ValueError(f"앱 자산 {base_bin}에는 쓰지 않는다. --sentence-start-out을 다른 경로로 지정할 것")
    base_data = base_bin.read_bytes()
    base = NgramBinReader(base_data)
    if base.word_id(SENTENCE_START_TOKEN) is not None:
        raise ValueError(f"{base_bin}에 이미 {SENTENCE_START_TOKEN} 행이 있다. 문장 시작 행이 없는 기준 파일에서 다시 만들 것")
    old_bigrams, old_trigrams = read_bin_rows(base)
    bos_bigram, bos_trigram = read_sentence_start_rows(start_dir)
    if not include_trigram:
        bos_trigram = []

    data, _vocab, _stats = build_ngram_bin(old_bigrams + bos_bigram, old_trigrams + bos_trigram)
    if len(data) > MAX_BIN_BYTES:
        raise ValueError(f"ko-ngram.bin 크기 {len(data)} bytes가 32MB 한도를 초과함")
    out_path.parent.mkdir(parents=True, exist_ok=True)
    out_path.write_bytes(data)

    new = NgramBinReader(out_path.read_bytes())
    new_bigrams, new_trigrams = read_bin_rows(new)
    new_bigram_by_prev = _group_bigrams(new_bigrams)
    new_trigram_by_pair = _group_trigrams(new_trigrams)
    for prev, entries in _group_bigrams(old_bigrams).items():
        if new_bigram_by_prev.get(prev) != entries:
            raise AssertionError(f"기존 bigram 행이 왕복 일치하지 않음: {prev}")
    for pair, entries in _group_trigrams(old_trigrams).items():
        if new_trigram_by_pair.get(pair) != entries:
            raise AssertionError(f"기존 trigram 행이 왕복 일치하지 않음: {pair}")
    print(
        f"[sentence-start] 기존 bigram {len(old_bigrams)}행·trigram {len(old_trigrams)}행이 "
        f"(prev→next,count) 순서까지 모두 일치"
    )

    bos_expected = sum(1 for r in bos_bigram if r[1] not in NEXT_EXCLUDE)
    bos_tri_expected = sum(1 for r in bos_trigram if r[2] not in NEXT_EXCLUDE)
    added_b = new.b_total - base.b_total
    added_t = new.t_total - base.t_total
    if added_b != bos_expected or added_t != bos_tri_expected:
        raise AssertionError(f"추가 행 수 불일치: bigram {added_b}/{bos_expected}, trigram {added_t}/{bos_tri_expected}")
    print(
        f"[sentence-start] 추가 bigram {added_b}행(소스 {len(bos_bigram)}행 중 NEXT_EXCLUDE 제외 {len(bos_bigram) - bos_expected}행), "
        f"추가 trigram {added_t}행(소스 {len(bos_trigram)}행 중 제외 {len(bos_trigram) - bos_tri_expected}행)"
    )
    print(
        f"[sentence-start] V {base.v} -> {new.v}, B {base.b_total} -> {new.b_total}, "
        f"P {base.p_total} -> {new.p_total}, T {base.t_total} -> {new.t_total}"
    )
    print(f"[sentence-start] 크기 {len(base_data)} -> {len(data)} bytes (+{len(data) - len(base_data)})")
    print(f"[sentence-start] 작성: {out_path} sha256={sha256_of(out_path)}")
    print(
        f"[sentence-start] {SENTENCE_START_TOKEN} bigram 조회: "
        + ", ".join(f"{w}:{c}" for w, c in new.bigram_next(SENTENCE_START_TOKEN)[:15])
    )
    for w1 in [row[1] for row in bos_bigram[:3]] if include_trigram else []:
        entries = trigram_next(new, SENTENCE_START_TOKEN, w1)
        print(f"[sentence-start] ({SENTENCE_START_TOKEN}, {w1}) trigram 조회: " + ", ".join(f"{w}:{c}" for w, c in entries))


def verify(bin_path: Path, pack_dir: Path) -> None:
    data = bin_path.read_bytes()
    reader = NgramBinReader(data)
    print(
        f"[verify] header OK magic={MAGIC.decode()} version={VERSION} "
        f"V={reader.v} B={reader.b_total} P={reader.p_total} T={reader.t_total}"
    )

    # (1) 헤더·오프셋 일관성
    if reader.word_offsets[0] != 0:
        raise AssertionError("wordOffsets[0] != 0")
    if reader.word_offsets[reader.v] != len(reader.blob):
        raise AssertionError("wordOffsets[V] != blob length")
    for i in range(reader.v):
        if reader.word_offsets[i] > reader.word_offsets[i + 1]:
            raise AssertionError(f"wordOffsets 비단조 at {i}")
    for i in range(reader.v):
        if reader.bi_start[i] > reader.bi_start[i + 1]:
            raise AssertionError(f"biStart 비단조 at {i}")
    if reader.bi_start[reader.v] != reader.b_total:
        raise AssertionError("biStart[V] != B")
    for i in range(reader.p_total):
        if reader.tri_start[i] > reader.tri_start[i + 1]:
            raise AssertionError(f"triStart 비단조 at {i}")
    if reader.tri_start[reader.p_total] != reader.t_total:
        raise AssertionError("triStart[P] != T")
    # 정렬 검사: 어휘는 바이트 오름차순, 쌍은 (a,b) 오름차순
    words = [reader.word(i) for i in range(reader.v)]
    words_bytes = [w.encode("utf-8") for w in words]
    if words_bytes != sorted(words_bytes):
        raise AssertionError("vocab이 바이트 오름차순으로 정렬돼 있지 않음")
    pairs = list(zip(reader.tri_a, reader.tri_b))
    if pairs != sorted(pairs):
        raise AssertionError("trigram (a,b) 쌍이 오름차순으로 정렬돼 있지 않음")
    print("[verify] 오프셋·정렬 일관성 OK")

    start_rows = sentence_start_row_count(reader)
    print(f"[verify] {SENTENCE_START_TOKEN} bigram 행 {start_rows}개")
    if start_rows == 0:
        print(f"[verify] 경고: {SENTENCE_START_DROPPED_WARNING}")

    # (2) 원본 TSV 대비 엔트리 수
    bigram_rows = read_bigram(pack_dir / "bigram.tsv")
    trigram_rows = read_trigram(pack_dir / "trigram.tsv")
    expected_b = sum(1 for _p, n, _c in bigram_rows if n not in NEXT_EXCLUDE)
    expected_t = sum(1 for _a, _b, n, _c in trigram_rows if n not in NEXT_EXCLUDE)
    if expected_b != reader.b_total:
        raise AssertionError(f"B 불일치: 기대={expected_b} 실제={reader.b_total}")
    if expected_t != reader.t_total:
        raise AssertionError(f"T 불일치: 기대={expected_t} 실제={reader.t_total}")
    print(f"[verify] 엔트리 수 일치: B={reader.b_total} (기대 {expected_b}), T={reader.t_total} (기대 {expected_t})")

    # (3) 조회 샘플
    print("[verify] bigram 조회:")
    for w in BIGRAM_LOOKUP_SAMPLES:
        results = reader.bigram_next(w)
        preview = ", ".join(f"{nw}:{c}" for nw, c in results[:5])
        print(f"  {w} -> [{preview}] (총 {len(results)}개)")

    print("[verify] trigram 샘플 5개:")
    sample_indices = [0, reader.p_total // 4, reader.p_total // 2, (reader.p_total * 3) // 4, reader.p_total - 1]
    seen = set()
    shown = 0
    for idx in sample_indices:
        if idx < 0 or idx >= reader.p_total or idx in seen:
            continue
        seen.add(idx)
        a = reader.word(reader.tri_a[idx])
        b = reader.word(reader.tri_b[idx])
        start, end = reader.tri_start[idx], reader.tri_start[idx + 1]
        entries = [(reader.word(reader.tri_next[i]), reader.tri_count[i]) for i in range(start, end)]
        preview = ", ".join(f"{nw}:{c}" for nw, c in entries[:5])
        print(f"  ({a}, {b}) -> [{preview}]")
        shown += 1
    print(f"[verify] trigram 샘플 {shown}개 출력 완료")


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--pack-dir", type=Path)
    parser.add_argument("--assets-dir", type=Path, default=Path("app/src/main/assets"))
    parser.add_argument("--verify", action="store_true")
    parser.add_argument("--allow-drop-sentence-start", action="store_true")
    parser.add_argument("--add-sentence-start", type=Path, metavar="DIR")
    parser.add_argument("--sentence-start-out", type=Path, default=DEFAULT_SENTENCE_START_OUT)
    parser.add_argument("--sentence-start-no-trigram", action="store_true")
    args = parser.parse_args()

    if args.add_sentence_start is not None:
        add_sentence_start(
            args.assets_dir / "korean" / "ko-ngram.bin",
            args.add_sentence_start,
            args.sentence_start_out,
            include_trigram=not args.sentence_start_no_trigram,
        )
        return
    if args.pack_dir is None:
        parser.error("--pack-dir is required unless --add-sentence-start is given")

    unigram_path = args.pack_dir / "unigram.tsv"
    bigram_path = args.pack_dir / "bigram.tsv"
    trigram_path = args.pack_dir / "trigram.tsv"

    print("입력 SHA256:")
    for p in (unigram_path, bigram_path, trigram_path):
        print(f"  {p.name}\t{sha256_of(p)}")

    unigram_rows = read_unigram(unigram_path)
    bigram_rows = read_bigram(bigram_path)
    trigram_rows = read_trigram(trigram_path)

    ngram_data, vocab, stats = build_ngram_bin(bigram_rows, trigram_rows)
    if len(ngram_data) > MAX_BIN_BYTES:
        print(
            f"오류: ko-ngram.bin 크기 {len(ngram_data)} bytes가 32MB 한도를 초과함",
            file=sys.stderr,
        )
        sys.exit(1)
    ngram_out = args.assets_dir / "korean" / "ko-ngram.bin"
    if sentence_start_row_count(NgramBinReader(ngram_data)) == 0:
        existing_start_rows = (
            sentence_start_row_count(NgramBinReader(ngram_out.read_bytes())) if ngram_out.exists() else 0
        )
        if existing_start_rows > 0:
            if not args.allow_drop_sentence_start:
                print(
                    f"오류: 기존 자산 {ngram_out}의 {SENTENCE_START_TOKEN} 행 {existing_start_rows}개가 사라진다. "
                    "의도했다면 --allow-drop-sentence-start로 다시 돌린 뒤 "
                    "docs/korean-input/korean-smart-input-ssot.md의 --add-sentence-start 단계를 이어서 실행하라",
                    file=sys.stderr,
                )
                sys.exit(1)
            print(f"경고: {SENTENCE_START_DROPPED_WARNING}", file=sys.stderr)

    vocab_out = args.assets_dir / "ko_base_vocab.tsv"
    vocab_bytes = write_vocab_tsv(unigram_rows, vocab_out)
    print(f"작성: {vocab_out} ({vocab_bytes} bytes, {UNIGRAM_ROWS}행 + 헤더 1행)")

    ngram_out.parent.mkdir(parents=True, exist_ok=True)
    ngram_out.write_bytes(ngram_data)
    print(f"작성: {ngram_out} ({len(ngram_data)} bytes)")
    print(
        f"통계: V={stats['V']} B={stats['B']} (bigram 소스 {stats['bigram_source_rows']}행 중 "
        f"제외 {stats['bigram_dropped_by_exclude']}행) P={stats['P']} T={stats['T']} "
        f"(trigram 소스 {stats['trigram_source_rows']}행 중 제외 {stats['trigram_dropped_by_exclude']}행)"
    )

    print("출력 SHA256:")
    for p in (vocab_out, ngram_out):
        print(f"  {p.name}\t{sha256_of(p)}")

    if args.verify:
        verify(ngram_out, args.pack_dir)


if __name__ == "__main__":
    main()
