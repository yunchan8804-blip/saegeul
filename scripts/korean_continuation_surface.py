# SPDX-License-Identifier: LGPL-2.1-or-later
# SPDX-FileCopyrightText: Copyright 2026 Yun Chan
"""검증된 decoded suffix를 새글 continuation wire 하나로 표면화한다.

이 adapter는 주어진 prefix에 속함이 이미 검증된 suffix만 받는다. 원문 충실도나
전체 모델 출력의 복원, 한국어 의미와 문장 품질은 판단하거나 보장하지 않는다.
"""

from __future__ import annotations

import re


MAX_PREFIX_UTF16_CODE_UNITS = 1024
_ASCII_SPACE = " "
_SPECIAL_MARKERS = ("<|", "|>")


def continuation_from_suffix(prefix: str, suffix: str) -> dict:
    """typed continuation wire 하나 또는 명시적 abstention 진단을 반환한다."""
    boundary_space_removed = len(suffix) - len(suffix.strip(_ASCII_SPACE))

    if not prefix.strip():
        return _abstain("blank_prefix", boundary_space_removed)
    if _utf16_code_units(prefix) > MAX_PREFIX_UTF16_CODE_UNITS:
        return _abstain("prefix_too_long", boundary_space_removed)
    if not suffix.strip():
        return _abstain("blank_suffix", boundary_space_removed)

    prefix_issue = _invalid_text_status(prefix, "prefix")
    if prefix_issue is not None:
        return _abstain(prefix_issue, boundary_space_removed)
    suffix_issue = _invalid_text_status(suffix, "suffix")
    if suffix_issue is not None:
        return _abstain(suffix_issue, boundary_space_removed)
    if _has_unsupported_boundary_whitespace(prefix, suffix):
        return _abstain("unsupported_boundary_whitespace", boundary_space_removed)

    payload = suffix.strip(_ASCII_SPACE)
    if _has_non_ascii_boundary_whitespace(payload):
        return _abstain("unsupported_boundary_whitespace", boundary_space_removed)
    if _normalize(payload).startswith(_normalize(prefix)):
        return _abstain("repeated_prefix", boundary_space_removed)

    if suffix.startswith(_ASCII_SPACE) or prefix.endswith(_ASCII_SPACE):
        join_mode = "NEXT_WORD"
        kind = "CONTINUATION"
    else:
        join_mode = "ATTACH"
        kind = "CONTINUATION_ATTACH"
    return {
        "suggestions": [f"{kind}\t{payload}"],
        "status": "accepted",
        "join_mode": join_mode,
        "payload": payload,
        "boundary_space_removed": boundary_space_removed,
    }


def _abstain(status: str, boundary_space_removed: int) -> dict:
    return {
        "suggestions": [],
        "status": status,
        "join_mode": None,
        "payload": None,
        "boundary_space_removed": boundary_space_removed,
    }


def _utf16_code_units(value: str) -> int:
    return len(value.encode("utf-16-le", errors="surrogatepass")) // 2


def _invalid_text_status(value: str, name: str) -> str | None:
    if any(0xD800 <= ord(character) <= 0xDFFF for character in value):
        return f"{name}_invalid_unicode_surrogate"
    if any(_is_iso_control(character) for character in value):
        return f"{name}_contains_control"
    if "\ufffd" in value:
        return f"{name}_contains_replacement_character"
    if "\u200b" in value or "\ufeff" in value:
        return f"{name}_contains_zero_width_character"
    if any(marker in value for marker in _SPECIAL_MARKERS):
        return f"{name}_contains_special_marker"
    return None


def _is_iso_control(character: str) -> bool:
    code_point = ord(character)
    return 0x0000 <= code_point <= 0x001F or 0x007F <= code_point <= 0x009F


def _has_unsupported_boundary_whitespace(prefix: str, suffix: str) -> bool:
    return _is_non_ascii_whitespace(prefix[-1]) or any(
        _is_non_ascii_whitespace(character) for character in (suffix[0], suffix[-1])
    )


def _has_non_ascii_boundary_whitespace(value: str) -> bool:
    return bool(value) and any(
        _is_non_ascii_whitespace(character) for character in (value[0], value[-1])
    )


def _is_non_ascii_whitespace(character: str) -> bool:
    return character != _ASCII_SPACE and character.isspace()


def _normalize(value: str) -> str:
    return re.sub(r"\s+", _ASCII_SPACE, value.strip())
