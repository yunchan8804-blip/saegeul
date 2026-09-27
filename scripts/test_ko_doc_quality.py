#!/usr/bin/env python3
"""Unit tests for scripts/ko_doc_quality.py. Run: python scripts/test_ko_doc_quality.py"""

import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

from ko_doc_quality import assess  # noqa: E402


class AssessTest(unittest.TestCase):
    def test_everyday_text_is_kept(self):
        text = (
            "오늘 퇴근하고 집에 가서 밥 먹었어. 내일은 친구랑 영화 보기로 했는데 "
            "아직 뭘 볼지 못 정했어. 너는 주말에 뭐 해? 날씨 좋으면 같이 산책 가자."
        )
        self.assertEqual(assess(text).reason, "ok")

    def test_news_style_text_is_kept(self):
        text = (
            "한국은행은 기준금리를 동결했다. 가계대출 증가세가 둔화된 영향이다. "
            "전문가들은 하반기 경기 흐름을 지켜봐야 한다고 말했다. 물가 상승률도 안정세를 보였다."
        )
        self.assertEqual(assess(text).reason, "ok")

    def test_strong_spam_root_rejects(self):
        self.assertEqual(assess("이따가 출장마사지 부르세요 친절하게 모십니다").reason, "spam")
        self.assertEqual(assess("강남 오피 추천 후기 모음").reason, "spam")
        self.assertEqual(assess("바카라 사이트 가입 쿠폰").reason, "spam")

    def test_office_words_are_not_spam(self):
        self.assertEqual(assess("오피스 프로그램 사용법을 정리했다. 오피니언 면도 읽었다.").reason, "ok")

    def test_short_roots_only_at_eojeol_start(self):
        self.assertEqual(assess("좋아하는 물건마다 사연이 있다. 그 사연은 바로 사람이다.").reason, "ok")
        self.assertEqual(assess("건마 후기 모음 정리").reason, "spam")

    def test_contact_dense_rejects(self):
        text = "상담 010-1234-5678 카톡: abc 텔레그램: def 지금 바로 연락해보세요"
        self.assertEqual(assess(text).reason, "contact")

    def test_commercial_boilerplate_rejects(self):
        text = (
            "지금 바로 예약 가능합니다. 궁금한 점은 고객센터로 문의 주세요. "
            "이번 달 할인 이벤트 진행 중입니다. 택배 배송은 이용시 무료입니다. "
            "제품 구매 후 후기를 남겨 주세요. 오늘 하루도 좋은 하루 보내세요."
        )
        self.assertEqual(assess(text).reason, "commercial")

    def test_repetitive_rejects(self):
        text = ". ".join(["최신 정보를 확인하세요"] * 6 + ["다른 문장 하나", "또 다른 문장 둘"]) + "."
        self.assertEqual(assess(text).reason, "repetitive")

    def test_empty_rejects(self):
        self.assertFalse(assess("   ").keep)


if __name__ == "__main__":
    unittest.main()
