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
    parser.add_argument("--pack-dir", type=Path, required=True)
    parser.add_argument("--assets-dir", type=Path, default=Path("app/src/main/assets"))
    parser.add_argument("--verify", action="store_true")
    args = parser.parse_args()

    unigram_path = args.pack_dir / "unigram.tsv"
    bigram_path = args.pack_dir / "bigram.tsv"
    trigram_path = args.pack_dir / "trigram.tsv"

    print("입력 SHA256:")
    for p in (unigram_path, bigram_path, trigram_path):
        print(f"  {p.name}\t{sha256_of(p)}")

    unigram_rows = read_unigram(unigram_path)
    bigram_rows = read_bigram(bigram_path)
    trigram_rows = read_trigram(trigram_path)

    vocab_out = args.assets_dir / "ko_base_vocab.tsv"
    vocab_bytes = write_vocab_tsv(unigram_rows, vocab_out)
    print(f"작성: {vocab_out} ({vocab_bytes} bytes, {UNIGRAM_ROWS}행 + 헤더 1행)")

    ngram_data, vocab, stats = build_ngram_bin(bigram_rows, trigram_rows)
    if len(ngram_data) > MAX_BIN_BYTES:
        print(
            f"오류: ko-ngram.bin 크기 {len(ngram_data)} bytes가 32MB 한도를 초과함",
            file=sys.stderr,
        )
        sys.exit(1)

    ngram_out = args.assets_dir / "korean" / "ko-ngram.bin"
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
