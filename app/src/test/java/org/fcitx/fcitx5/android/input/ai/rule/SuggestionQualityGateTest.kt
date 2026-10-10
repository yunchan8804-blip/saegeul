/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 YunChan
 */
package org.fcitx.fcitx5.android.input.ai.rule

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SuggestionQualityGateTest {

    @Test
    fun rejectsUngrammaticalSentenceCompletionOnly() {
        // ACC-02: "회의 자료를" + "감사합니다" (accusative + intransitive).
        val candidate = "회의 자료를 감사합니다."
        assertEquals(
            SuggestionQualityGate.RejectionReason.UNGRAMMATICAL,
            SuggestionQualityGate.evaluate(candidate, context = "", isSentenceCompletion = true)
        )
        // 문장 후보가 아니면(단어 줄) 문법 검사를 하지 않는다.
        assertNull(SuggestionQualityGate.evaluate(candidate, context = "", isSentenceCompletion = false))
    }

    @Test
    fun acceptsGrammaticallySoundSentenceCompletion() {
        assertTrue(SuggestionQualityGate.accepts("확인해서 다시 말씀드릴게요.", context = "", isSentenceCompletion = true))
    }

    @Test
    fun acceptsShortCasualSentenceAfterAContextThatContainsCelebrationVerb() {
        // 문맥의 "축하해"는 '축하' + '하다'의 활용이지 '하' 어간 중복이 아니다.
        assertTrue(
            SuggestionQualityGate.accepts("잘 보내!", context = "생일 축하해! 올 한 해도 ", isSentenceCompletion = true)
        )
        assertNull(
            SuggestionQualityGate.evaluate("잘 보내!", context = "생일 축하해! 올 한 해도 ", isSentenceCompletion = true)
        )
    }

    @Test
    fun acceptsShortCasualCommandAndGreetingSentences() {
        val cases = listOf(
            "잘 보내!" to "생일 축하해! 올 한 해도 ",
            "조심히 와!" to "집에 도착하면 연락해. ",
            "푹 쉬어." to "오늘 고생 많았어. ",
            "맛있게 먹어~" to "점심 뭐 먹을 거야? ",
            "잘 자!" to "내일 아침에 보자. ",
            "수고했어." to "오늘 일 다 끝났어. "
        )
        for ((candidate, context) in cases) {
            assertNull(
                "'$context' + '$candidate'",
                SuggestionQualityGate.evaluate(candidate, context, isSentenceCompletion = true)
            )
            assertNull(candidate, SuggestionQualityGate.evaluate(candidate, "", isSentenceCompletion = true))
        }
    }

    @Test
    fun stillRejectsStemDuplicationThatLooksLikeCelebrationVerb() {
        assertEquals(
            SuggestionQualityGate.RejectionReason.UNGRAMMATICAL,
            SuggestionQualityGate.evaluate("안녕하해요.", context = "생일 축하해! ", isSentenceCompletion = true)
        )
        assertEquals(
            SuggestionQualityGate.RejectionReason.UNGRAMMATICAL,
            SuggestionQualityGate.evaluate("준비하했어.", context = "", isSentenceCompletion = true)
        )
    }

    @Test
    fun rejectsKnownSpacingIssuesRegardlessOfSentenceFlag() {
        assertEquals(SuggestionQualityGate.RejectionReason.SPACING, SuggestionQualityGate.evaluate("할수있어요"))
        assertEquals(
            SuggestionQualityGate.RejectionReason.SPACING,
            SuggestionQualityGate.evaluate("자신감있게 발표했어요.", isSentenceCompletion = true)
        )
    }

    @Test
    fun rejectsDeuridaThatWouldBeDetachedFromItsNoun() {
        assertEquals(
            SuggestionQualityGate.RejectionReason.SPACING,
            SuggestionQualityGate.evaluate("드립니다", context = "확인 부탁 ")
        )
        assertEquals(
            SuggestionQualityGate.RejectionReason.SPACING,
            SuggestionQualityGate.evaluate("드리겠습니다.", context = "확인 부탁 ", isSentenceCompletion = true)
        )
        assertTrue(SuggestionQualityGate.accepts("드립니다", context = "커피 "))
        assertTrue(SuggestionQualityGate.accepts("드렸습니다.", context = "서류 ", isSentenceCompletion = true))
        assertTrue(SuggestionQualityGate.accepts("드립니다", context = "선물을 "))
        assertTrue(SuggestionQualityGate.accepts("드립니다", context = "확인 부탁"))
    }

    @Test
    fun rejectsConsecutiveDuplicateWord() {
        assertEquals(SuggestionQualityGate.RejectionReason.DUPLICATED_WORD, SuggestionQualityGate.evaluate("회의 회의 끝나고"))
        assertFalse(SuggestionQualityGate.accepts("회의 회의 끝나고"))
    }

    @Test
    fun acceptsNonAdjacentRepeatedWord() {
        // "네"가 인접하지 않으면(사이에 다른 어절이 있으면) 반복 거부 대상이 아니다.
        assertTrue(SuggestionQualityGate.accepts("네 알겠습니다 네"))
    }

    @Test
    fun rejectsHangulSyllableMixedWithCompatJamo() {
        assertEquals(SuggestionQualityGate.RejectionReason.MIXED_JAMO, SuggestionQualityGate.evaluate("안녕ㅎ"))
        assertEquals(SuggestionQualityGate.RejectionReason.MIXED_JAMO, SuggestionQualityGate.evaluate("고마워ㅜ 진짜"))
    }

    @Test
    fun acceptsJamoOnlyWord() {
        assertTrue(SuggestionQualityGate.accepts("ㅋㅋ"))
        assertTrue(SuggestionQualityGate.accepts("ㅠㅠ"))
        assertTrue(SuggestionQualityGate.accepts("ㅇㅋ"))
    }

    @Test
    fun rejectsKnownDeterministicTypo() {
        // "됬어요" -> "됐어요" (KoreanTypoCorrectionEngine.normalizeMorphologicalTypo).
        assertEquals(SuggestionQualityGate.RejectionReason.KNOWN_TYPO, SuggestionQualityGate.evaluate("됬어요"))
        // "할께요" -> "할게요" (ㄹ받침 + 께요 -> 게요 형태소 규칙).
        assertEquals(SuggestionQualityGate.RejectionReason.KNOWN_TYPO, SuggestionQualityGate.evaluate("지금 바로 출발할께요."))
        // "어의없어서" -> "어이없어서", "몰겠어요" -> "모르겠어요" (표준어 규정 오표기 어간 보강).
        assertEquals(SuggestionQualityGate.RejectionReason.KNOWN_TYPO, SuggestionQualityGate.evaluate("진짜 어의없어서 말이 안 나와요."))
        assertEquals(SuggestionQualityGate.RejectionReason.KNOWN_TYPO, SuggestionQualityGate.evaluate("몰겠어요"))
    }

    @Test
    fun doesNotRejectTypoUnknownToTheEngine() {
        // 교정 엔진 사전/규칙에 없는 1회성 오타는 이번 게이트 범위 밖이다.
        assertTrue(SuggestionQualityGate.accepts("피곤핸요"))
    }

    @Test
    fun acceptsOrdinaryCandidate() {
        assertTrue(SuggestionQualityGate.accepts("안녕하세요"))
        assertTrue(SuggestionQualityGate.accepts("오늘 회의 자료 준비해서 보내드릴게요.", isSentenceCompletion = true))
    }
}
