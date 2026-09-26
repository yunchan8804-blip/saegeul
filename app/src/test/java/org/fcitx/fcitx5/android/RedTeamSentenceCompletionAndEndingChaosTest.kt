/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 YunChan
 */
package org.fcitx.fcitx5.android

import org.fcitx.fcitx5.android.input.ai.KoreanSentenceEndingExtractor
import org.fcitx.fcitx5.android.input.ai.PersonalSentenceCompletionGate
import org.junit.Assert
import org.junit.Test

/**
 * Red Team Adversarial Unit Tests (Wave 12):
 * 1. KoreanSentenceEndingExtractor negative or zero limit never throws IllegalArgumentException.
 * 2. KoreanSentenceEndingExtractor does not falsely treat candidate nouns as endings.
 * 3. PersonalSentenceCompletionGate enforces word boundary preventing merged noun continuation.
 * 4. PersonalSentenceCompletionGate recognizes fullwidth and tilde boundaries.
 */
class RedTeamSentenceCompletionAndEndingChaosTest {

    /**
     * Test 1: korean sentence ending extractor negative or zero limit never throws IllegalArgumentException
     * Passing limit <= 0 should safely return emptyList() instead of crashing with IllegalArgumentException.
     */
    @Test
    fun `korean sentence ending extractor negative or zero limit never throws IllegalArgumentException`() {
        val sentences = listOf("오늘 회의 참석합니다.", "내일 뵙겠습니다.")
        val negativeResult = KoreanSentenceEndingExtractor.topEndings(sentences, limit = -1)
        Assert.assertEquals(emptyList<String>(), negativeResult)

        val zeroResult = KoreanSentenceEndingExtractor.topEndings(sentences, limit = 0)
        Assert.assertEquals(emptyList<String>(), zeroResult)
    }

    /**
     * Test 2: korean sentence ending extractor does not falsely treat candidate nouns as endings
     * Nouns like "대선 후보자" or "초보자" must not falsely extract suggestive ending "보자".
     * Genuine endings like "내일 영화 보자" or "밥 먹자" must properly extract "보자" and "먹자".
     */
    @Test
    fun `korean sentence ending extractor does not falsely treat candidate nouns as endings`() {
        Assert.assertNotEquals("보자", KoreanSentenceEndingExtractor.endingOf("대선 후보자"))
        Assert.assertNotEquals("보자", KoreanSentenceEndingExtractor.endingOf("초보자"))

        Assert.assertEquals("보자", KoreanSentenceEndingExtractor.endingOf("내일 영화 보자"))
        Assert.assertEquals("먹자", KoreanSentenceEndingExtractor.endingOf("밥 먹자"))
    }

    /**
     * Test 3: personal sentence completion gate enforces word boundary preventing merged noun continuation
     * Context "오늘 회의" followed by merged noun "실" -> "오늘 회의실 예약했습니다" must be rejected (false).
     * Normal word-separated expansion "오늘 회의 참석하겠습니다" must be accepted (true).
     */
    @Test
    fun `personal sentence completion gate enforces word boundary preventing merged noun continuation`() {
        val context = "오늘 회의"
        val candidate = "오늘 회의실 예약했습니다"
        Assert.assertFalse(
            "Merged noun continuation without word boundary must be rejected",
            PersonalSentenceCompletionGate.isContinuation(context, candidate)
        )

        val candidateValid = "오늘 회의 참석하겠습니다"
        Assert.assertTrue(
            "Valid continuation respecting word boundary must be accepted",
            PersonalSentenceCompletionGate.isContinuation(context, candidateValid)
        )
    }

    /**
     * Test 4: personal sentence completion gate recognizes fullwidth and tilde boundaries
     * Context with tilde '~' or fullwidth punctuation ('。', '！', '？') must isolate the active sentence segment.
     */
    @Test
    fun `personal sentence completion gate recognizes fullwidth and tilde boundaries`() {
        val context = "밥 먹었어~ 내일 몇 시에 만날까"
        val candidate = "내일 몇 시에 만날까요?"
        Assert.assertTrue(
            "Tilde sentence boundary must correctly isolate the trailing active sentence",
            PersonalSentenceCompletionGate.isContinuation(context, candidate)
        )

        val contextFullwidthPeriod = "밥 먹었어。 내일 몇 시에 만날까"
        Assert.assertTrue(
            "Fullwidth period boundary must correctly isolate the trailing active sentence",
            PersonalSentenceCompletionGate.isContinuation(contextFullwidthPeriod, candidate)
        )

        val contextFullwidthExclamation = "밥 먹었어！ 내일 몇 시에 만날까"
        Assert.assertTrue(
            "Fullwidth exclamation boundary must correctly isolate the trailing active sentence",
            PersonalSentenceCompletionGate.isContinuation(contextFullwidthExclamation, candidate)
        )

        val contextFullwidthQuestion = "밥 먹었어？ 내일 몇 시에 만날까"
        Assert.assertTrue(
            "Fullwidth question mark boundary must correctly isolate the trailing active sentence",
            PersonalSentenceCompletionGate.isContinuation(contextFullwidthQuestion, candidate)
        )
    }
}
