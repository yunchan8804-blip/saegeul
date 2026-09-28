/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 YunChan
 */
package org.fcitx.fcitx5.android.input.ai.rule

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 기기 생성 문장 붙여쓰기 오류 린트([KoreanSpacingLint])의 회귀 방어선.
 * 2026-09-27 실기기에서 나온 "...자신감있게 하면 되지 않을까싶어요." 사례를 잡아야 하고,
 * "맛있게"/"재미있게"처럼 원래 붙여 쓰는 정상 표현은 건드리지 않아야 한다.
 */
class KoreanSpacingLintTest {

    @Test
    fun `catches known attachment errors`() {
        val badExamples = listOf(
            "렬하고 자신감있게 하면 되지 않을까싶어요.",
            "할수있어요.",
            "먹을것같아.",
            "책임감있게 진행하겠습니다.",
            "여유있게 준비할게요.",
            "관심있는 분야입니다.",
            "능력있는 사람입니다.",
            "가치있는 시간이었어요.",
            "매력있는 제안이네요.",
            "개성있는 디자인이에요.",
            "그런지싶어서 다시 물어봤어요.",
            "그런듯하게 말했어요."
        )
        badExamples.forEach { text ->
            assertTrue("'$text' 는 붙여쓰기 오류로 잡혀야 한다", KoreanSpacingLint.hasSpacingIssue(text))
        }
    }

    @Test
    fun `passes normal attached adjectives and correctly spaced text`() {
        val cleanExamples = listOf(
            "맛있게 드세요.",
            "재미있게 놀았어요.",
            "멋있는 사람이네요.",
            "할 수 있어요.",
            "먹을 것 같아.",
            "의미있는 하루였어요."
        )
        cleanExamples.forEach { text ->
            assertFalse("'$text' 는 정상 표기로 통과해야 한다", KoreanSpacingLint.hasSpacingIssue(text))
        }
    }

    @Test
    fun `catches deurida detached from the noun it attaches to`() {
        val detached = listOf(
            "확인 부탁 " to "드립니다",
            "확인 부탁 " to "드려요",
            "확인 부탁 " to "드리겠습니다",
            "나중에 연락 " to "드릴게요",
            "정말 감사 " to "드렸어요",
            "먼저 인사 " to "드린다"
        )
        detached.forEach { (context, candidate) ->
            assertTrue(
                "'$context' 뒤 '$candidate' 는 앞 명사와 붙여 써야 한다",
                KoreanSpacingLint.detachesDeurida(context, candidate)
            )
        }
    }

    @Test
    fun `keeps deurida outside explicit noun attachments and a space-separated join`() {
        val spacedCorrectly = listOf(
            "커피 " to "드립니다",
            "서류 " to "드렸어요",
            "선물을 " to "드립니다",
            "선생님께 " to "드려요",
            "일정 " to "드릴게요",
            "확인 부탁" to "드립니다",
            "확인 부탁 " to "드디어",
            "" to "드립니다"
        )
        spacedCorrectly.forEach { (context, candidate) ->
            assertFalse(
                "'$context' 뒤 '$candidate' 는 띄어쓰기 오류가 아니다",
                KoreanSpacingLint.detachesDeurida(context, candidate)
            )
        }
    }
}
