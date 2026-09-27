#!/usr/bin/env python3
"""Everyday-context probe for the bundled Korean next-eojeol pack (KONGRAM1).

The keyboard is used for messages, so after an everyday context the next-eojeol
candidates should read like a person typing, not like a shop page. This script
reads one or more `ko-ngram.bin` files and, for a fixed list of casual contexts,
scores the top candidates:

  - spam:   a candidate contains a spam root from ko_doc_quality.py
  - formal: in a casual context, a top-5 candidate is a 합쇼체 ending
            (…니다/…니까/…십시오) or written-register filler (및, 등, 통해, …)
  - empty:  the context produced no candidate

Usage:
  python scripts/eval-ko-everyday-probes.py app/src/main/assets/korean/ko-ngram.bin [other.bin ...] [--show]
"""

from __future__ import annotations

import argparse
import bisect
import struct
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from ko_doc_quality import STRONG_SPAM_EOJEOL, STRONG_SPAM_ROOTS  # noqa: E402

# Casual contexts (반말·해요체 메신저 문맥). The last one or two eojeols are the query.
PROBES = [
    "오늘", "오늘 저녁", "나 지금", "지금 집에", "내일 몇", "밥 먹었어", "고마워", "진짜", "혹시 시간",
    "회의 끝나고", "퇴근하고", "엄마", "배고파", "이따가", "그럼", "주말에", "택배", "선택", "우리 내일",
    "그거", "너", "왜", "빨리", "좀", "근데", "아까", "내가", "우리", "지금", "어디야", "뭐해", "언제",
    "괜찮아", "미안해", "알겠어", "잘 자", "수고했어", "도착하면", "시간 되면", "나중에", "혹시",
    "오랜만에", "같이", "벌써", "아직", "생각보다", "다음 주에", "점심 뭐", "저녁에", "집에 가서",
    "학교 끝나고", "주말에 뭐", "영화 보러", "커피 한잔", "카톡 확인", "사진 보내", "연락 줘",
    "보고 싶어", "많이 바빠", "조심히",
]

FORMAL_ENDINGS = ("니다", "니까", "십시오", "시길", "시기")
WRITTEN_FILLERS = {
    "및", "등", "등을", "등의", "등이", "통해", "위해", "대한", "따라", "이용시", "경우", "관련",
    "해당", "각종", "다양한", "제공", "진행", "가능한", "가능", "이용", "서비스", "소개할",
}


class Pack:
    def __init__(self, path: Path):
        d = path.read_bytes()
        if d[:8] != b"KONGRAM1":
            raise SystemExit(f"{path}: bad magic")
        _ver, v, b, p, t = struct.unpack_from("<5i", d, 8)
        pos = 28

        def ints(n):
            nonlocal pos
            a = struct.unpack_from(f"<{n}i", d, pos)
            pos += 4 * n
            return a

        off = ints(v + 1)
        blob = d[pos:pos + off[v]]
        pos = (pos + off[v] + 3) & ~3
        self.words = [blob[off[i]:off[i + 1]].decode("utf-8") for i in range(v)]
        self.keys = [w.encode("utf-8") for w in self.words]
        self.bi_s, self.bi_n = ints(v + 1), ints(b)
        ints(b)  # bigram counts (order already by frequency)
        tri_a, tri_b = ints(p), ints(p)
        self.tri_s, self.tri_n = ints(p + 1), ints(t)
        self.pairs = list(zip(tri_a, tri_b))

    def _id(self, w: str) -> int:
        k = w.encode("utf-8")
        i = bisect.bisect_left(self.keys, k)
        return i if i < len(self.keys) and self.keys[i] == k else -1

    def next_words(self, prev2: str | None, prev1: str, limit: int = 8) -> list[str]:
        i1 = self._id(prev1)
        if i1 < 0:
            return []
        out: list[str] = []
        if prev2:
            i2 = self._id(prev2)
            if i2 >= 0:
                j = bisect.bisect_left(self.pairs, (i2, i1))
                if j < len(self.pairs) and self.pairs[j] == (i2, i1):
                    out += [self.words[self.tri_n[k]] for k in range(self.tri_s[j], self.tri_s[j + 1])]
        for k in range(self.bi_s[i1], self.bi_s[i1 + 1]):
            w = self.words[self.bi_n[k]]
            if w not in out:
                out.append(w)
        return out[:limit]


def is_spam(word: str) -> bool:
    return any(r in word for r in STRONG_SPAM_ROOTS) or bool(STRONG_SPAM_EOJEOL.search(word))


def is_formal(word: str) -> bool:
    return word.endswith(FORMAL_ENDINGS) or word in WRITTEN_FILLERS


def score(pack: Pack) -> tuple[dict[str, float], list[tuple[str, list[str]]]]:
    rows = []
    spam = formal = top5 = empty = 0
    for probe in PROBES:
        ws = probe.split()
        cands = pack.next_words(ws[-2] if len(ws) > 1 else None, ws[-1])
        rows.append((probe, cands))
        if not cands:
            empty += 1
        spam += sum(1 for c in cands if is_spam(c))
        head = cands[:5]
        top5 += len(head)
        formal += sum(1 for c in head if is_formal(c))
    n = len(PROBES)
    return {
        "probes": n,
        "spam_candidates": spam,
        "formal_share_top5": formal / top5 if top5 else 0.0,
        "empty_share": empty / n,
    }, rows


def main() -> None:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("packs", nargs="+", type=Path)
    ap.add_argument("--show", action="store_true", help="print every probe's top candidates")
    args = ap.parse_args()
    for path in args.packs:
        metrics, rows = score(Pack(path))
        print(f"{path}: spam={metrics['spam_candidates']} "
              f"formal_top5={metrics['formal_share_top5']:.1%} empty={metrics['empty_share']:.1%} "
              f"(probes={metrics['probes']})")
        if args.show:
            for probe, cands in rows:
                print(f"  [{probe}] -> {' | '.join(cands) if cands else '(없음)'}")


if __name__ == "__main__":
    main()
