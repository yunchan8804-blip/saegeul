# SPDX-License-Identifier: LGPL-2.1-or-later
# SPDX-FileCopyrightText: Copyright 2026 Yun Chan

import unittest

from korean_continuation_surface import continuation_from_suffix


class KoreanContinuationSurfaceTest(unittest.TestCase):
    def test_next_word_when_suffix_begins_with_ascii_space(self):
        result = continuation_from_suffix("내가 뭘", " 잘못했는지 알려줘")

        self.assertEqual(["CONTINUATION\t잘못했는지 알려줘"], result["suggestions"])
        self.assertEqual("accepted", result["status"])
        self.assertEqual("NEXT_WORD", result["join_mode"])
        self.assertEqual("잘못했는지 알려줘", result["payload"])
        self.assertEqual(1, result["boundary_space_removed"])

    def test_next_word_when_prefix_ends_with_ascii_space(self):
        result = continuation_from_suffix("내가 뭘 ", "어떻게 해야 할지 모르겠어")

        self.assertEqual(["CONTINUATION\t어떻게 해야 할지 모르겠어"], result["suggestions"])
        self.assertEqual("NEXT_WORD", result["join_mode"])
        self.assertEqual(0, result["boundary_space_removed"])

    def test_attach_when_neither_side_has_ascii_boundary_space(self):
        result = continuation_from_suffix("내가 뭘", "해야 할지 모르겠어")

        self.assertEqual(["CONTINUATION_ATTACH\t해야 할지 모르겠어"], result["suggestions"])
        self.assertEqual("ATTACH", result["join_mode"])

    def test_ascii_boundary_spaces_are_removed_only_from_payload(self):
        prefix = "자료를 확인하고"
        suffix = "  내부  공백은  보존해  "

        result = continuation_from_suffix(prefix, suffix)

        self.assertEqual(prefix, "자료를 확인하고")
        self.assertEqual(suffix, "  내부  공백은  보존해  ")
        self.assertEqual("내부  공백은  보존해", result["payload"])
        self.assertEqual(4, result["boundary_space_removed"])

    def test_blank_and_too_long_prefixes_abstain(self):
        blank = continuation_from_suffix(" \t", "계속해")
        long_prefix = continuation_from_suffix("가" * 1025, "계속해")
        astral_boundary = continuation_from_suffix("가" * 1023 + "😀", "계속해")
        exact_astral_limit = continuation_from_suffix("가" * 1022 + "😀", "계속해")

        self.assertEqual("blank_prefix", blank["status"])
        self.assertEqual("prefix_too_long", long_prefix["status"])
        self.assertEqual("prefix_too_long", astral_boundary["status"])
        self.assertEqual("accepted", exact_astral_limit["status"])

    def test_blank_suffix_abstains(self):
        self.assertEqual(
            "blank_suffix",
            continuation_from_suffix("내가 뭘", " \t ")["status"],
        )

    def test_controls_replacement_and_special_markers_abstain(self):
        cases = (
            ("내가\u0000 뭘", "계속해", "prefix_contains_control"),
            ("내가 뭘", "계속\u0085해", "suffix_contains_control"),
            ("내가\ufffd 뭘", "계속해", "prefix_contains_replacement_character"),
            ("내가 뭘", "계속\u200b해", "suffix_contains_zero_width_character"),
            ("내가 뭘", "계속\ufeff해", "suffix_contains_zero_width_character"),
            ("내가 <|assistant", "계속해", "prefix_contains_special_marker"),
            ("내가 뭘", "계속|>해", "suffix_contains_special_marker"),
        )
        for prefix, suffix, status in cases:
            with self.subTest(status=status):
                result = continuation_from_suffix(prefix, suffix)
                self.assertEqual([], result["suggestions"])
                self.assertEqual(status, result["status"])

    def test_lone_unicode_surrogates_abstain_per_input(self):
        prefix = continuation_from_suffix("내가\ud800 뭘", "계속해")
        suffix = continuation_from_suffix("내가 뭘", "계속\udfff해")

        self.assertEqual("prefix_invalid_unicode_surrogate", prefix["status"])
        self.assertEqual("suffix_invalid_unicode_surrogate", suffix["status"])
        self.assertEqual([], prefix["suggestions"])
        self.assertEqual([], suffix["suggestions"])

    def test_non_ascii_boundary_whitespace_abstains_without_rewriting(self):
        cases = (
            ("내가 뭘\u00a0", "계속해"),
            ("내가 뭘", "\u00a0계속해"),
            ("내가 뭘", "계속해\u00a0"),
            ("내가 뭘", " \u00a0계속해"),
        )
        for prefix, suffix in cases:
            with self.subTest(prefix=prefix, suffix=suffix):
                result = continuation_from_suffix(prefix, suffix)
                self.assertEqual("unsupported_boundary_whitespace", result["status"])
                self.assertEqual([], result["suggestions"])

    def test_repeated_normalized_prefix_abstains(self):
        result = continuation_from_suffix("내가  뭘", "내가 뭘 어떻게 해야 할지")

        self.assertEqual("repeated_prefix", result["status"])
        self.assertEqual([], result["suggestions"])

    def test_internal_whitespace_is_preserved(self):
        result = continuation_from_suffix("내가 뭘", "해야  할지\u2002모르겠어")

        self.assertEqual("해야  할지\u2002모르겠어", result["payload"])
        self.assertEqual(["CONTINUATION_ATTACH\t해야  할지\u2002모르겠어"], result["suggestions"])


if __name__ == "__main__":
    unittest.main()
