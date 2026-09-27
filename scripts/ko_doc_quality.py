#!/usr/bin/env python3
"""Document-level quality gate for the Korean web corpus (FineWeb-2 kor_Hang).

The bundled next-eojeol pack is counted from web documents, and Korean web
text carries a lot of SEO spam (adult massage, gambling, loan and drug ads)
and shop boilerplate. Counting those documents made the keyboard suggest
"이따가 → 출장마사지" and "나 지금 → 가능합니다". This gate rejects a whole
document before any of its sentences reach the n-gram counter.

A document is rejected when any rule fires:
  - spam:        it contains a strong spam root (never used in ordinary text).
  - contact:     phone numbers, messenger IDs and URLs are dense.
  - commercial:  a large share of its sentences carry shop/booking vocabulary.
  - repetitive:  it repeats the same sentences (templated listings).

`assess` is pure so the thresholds can be unit-tested and calibrated.
"""

from __future__ import annotations

import re
from dataclasses import dataclass

# Roots that practically only appear in spam/ad documents. A single hit rejects.
STRONG_SPAM_ROOTS = (
    "출장마사지", "출장안마", "출장샵", "출장만남", "출장아가씨", "오피스텔안마",
    "휴게텔", "키스방", "립카페", "안마방", "조건만남", "애인대행",
    "바카라", "카지노사이트", "토토사이트", "사설토토", "파워볼", "홀덤사이트",
    "해외배팅", "스포츠토토분석",
    "비아그라", "시알리스", "레비트라", "발기부전", "성인용품",
    "흥신소", "심부름센터", "폰테크", "내구제", "작업대출", "무직자대출", "급전대출",
    "리딩방", "코인리딩", "주식리딩", "후불제", "텔레그램문의", "카톡문의",
)
# Two-syllable roots collide with ordinary words as substrings (오피스, 물건마다),
# so they only count at the start of an eojeol ("오피" also after a place name).
STRONG_SPAM_EOJEOL = re.compile(
    r"(?:^|[^가-힣])(?:[가-힣]{0,3}오피(?:걸|후기|추천|사이트|정보|방)?(?![가-힣])"
    r"|건마(?:샵|후기|추천)?(?![가-힣])|조루|몰카|먹튀|야동)"
)

PHONE = re.compile(r"(?<!\d)0\d{1,2}[-.\s)]?\d{3,4}[-.\s]?\d{4}(?!\d)")
MESSENGER_ID = re.compile(r"(?:카톡|카카오톡|텔레그램|텔레|라인)\s*(?:아이디|ID|id)?\s*[:：@]")
URL = re.compile(r"https?://|www\.")

COMMERCE_TERMS = (
    "문의", "상담", "예약", "할인", "이벤트", "최저가", "쿠폰", "배송", "구매", "주문",
    "결제", "가격", "이용시", "이용 시", "신청", "혜택", "특가", "무료체험", "고객센터",
    "판매", "상품", "제품", "견적", "업체", "매장", "방문",
)

SENTENCE_SPLIT = re.compile(r"[\r\n]+|[.?!…]+")

# Calibrated on a FineWeb-2 kor_Hang sample (see build report doc_filter stats).
CONTACT_PER_1K_CHARS = 1.5
URL_PER_1K_CHARS = 4.0
COMMERCIAL_SENTENCE_SHARE = 0.35
COMMERCIAL_MIN_SENTENCES = 5
REPETITIVE_UNIQUE_SHARE = 0.5
REPETITIVE_MIN_SENTENCES = 8


@dataclass(frozen=True)
class Verdict:
    keep: bool
    reason: str  # "ok", "spam", "contact", "commercial", "repetitive", "empty"


def _sentences(text: str) -> list[str]:
    return [s.strip() for s in SENTENCE_SPLIT.split(text) if len(s.strip()) >= 4]


def assess(text: str) -> Verdict:
    if not text or not text.strip():
        return Verdict(False, "empty")
    compact = text.replace(" ", "")
    if any(root in compact for root in STRONG_SPAM_ROOTS) or STRONG_SPAM_EOJEOL.search(text):
        return Verdict(False, "spam")

    per_1k = 1000.0 / max(len(text), 1)
    contacts = len(PHONE.findall(text)) + len(MESSENGER_ID.findall(text))
    if contacts and contacts * per_1k >= CONTACT_PER_1K_CHARS:
        return Verdict(False, "contact")
    urls = len(URL.findall(text))
    if urls and urls * per_1k >= URL_PER_1K_CHARS:
        return Verdict(False, "contact")

    sentences = _sentences(text)
    if len(sentences) >= COMMERCIAL_MIN_SENTENCES:
        commercial = sum(1 for s in sentences if any(term in s for term in COMMERCE_TERMS))
        if commercial / len(sentences) >= COMMERCIAL_SENTENCE_SHARE:
            return Verdict(False, "commercial")
    if len(sentences) >= REPETITIVE_MIN_SENTENCES:
        if len(set(sentences)) / len(sentences) < REPETITIVE_UNIQUE_SHARE:
            return Verdict(False, "repetitive")
    return Verdict(True, "ok")
