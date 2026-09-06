/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 YunChan
 */
package org.fcitx.fcitx5.android.input.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for KoreanSentenceContinuation.
 * Verifies that typed word(s) are always kept as a literal prefix of the completion,
 * that cold-start 하다-명사 ending attachment works with no learned data, and that
 * personal n-gram chaining surfaces learned continuations.
 */
class KoreanSentenceContinuationTest {

    private val defaultPackage = "com.example.test"

    @Test
    fun honorificEndingAttachmentPreservesInputPrefix() {
        val engine = KoreanSentenceContinuation()

        val result = engine.continuations(listOf("회의", "참석"), ContinuationTone.Honorific, defaultPackage)

        assertTrue(result.contains("회의 참석하겠습니다"))
        result.forEach { assertTrue(it.startsWith("회의 참석")) }
    }

    @Test
    fun honorificEndingAttachmentSingleWordContext() {
        val engine = KoreanSentenceContinuation()

        val result = engine.continuations(listOf("확인"), ContinuationTone.Honorific, defaultPackage)

        assertTrue(result.contains("확인하겠습니다"))
        assertTrue(result.contains("확인했습니다"))
    }

    @Test
    fun informalEndingAttachmentForMessengerTone() {
        val engine = KoreanSentenceContinuation()

        val result = engine.continuations(listOf("확인"), ContinuationTone.Informal, defaultPackage)

        assertTrue(result.contains("확인하자") || result.contains("확인할게"))
        result.forEach { assertTrue(it.startsWith("확인")) }
    }

    @Test
    fun ngramChainingSurfacesLearnedContinuation() {
        val ngram = PersonalNgramModel(clock = { 1_000_000_000L })
        ngram.learn("회의 참석 완료했습니다", defaultPackage)
        val engine = KoreanSentenceContinuation(ngram = ngram)

        val result = engine.continuations(listOf("회의", "참석"), ContinuationTone.Honorific, defaultPackage, limit = 6)

        assertTrue(result.contains("회의 참석 완료했습니다"))
        result.forEach { assertTrue(it.startsWith("회의 참석")) }
    }

    @Test
    fun emptyContextTailReturnsEmptyList() {
        val engine = KoreanSentenceContinuation()

        val result = engine.continuations(emptyList(), ContinuationTone.Honorific, defaultPackage)

        assertTrue(result.isEmpty())
    }

    @Test
    fun terminalLastWordDoesNotDoubleAttachEnding() {
        val engine = KoreanSentenceContinuation()

        val result = engine.continuations(listOf("오늘", "먹었어"), ContinuationTone.Honorific, defaultPackage)

        assertFalse(result.contains("오늘 먹었어하겠습니다"))
        assertFalse(result.contains("오늘 먹었어했습니다"))
        assertFalse(result.contains("오늘 먹었어합니다"))
    }

    @Test
    fun allResultsPreserveBasePrefix() {
        val ngram = PersonalNgramModel(clock = { 1_000_000_000L })
        ngram.learn("회의 참석 완료했습니다", defaultPackage)
        val collocation = KoreanCollocationModel()
        val engine = KoreanSentenceContinuation(ngram = ngram, collocation = collocation)

        val result = engine.continuations(listOf("회의", "참석"), ContinuationTone.Honorific, defaultPackage, limit = 10)

        assertTrue(result.isNotEmpty())
        result.forEach { assertTrue(it.startsWith("회의 참석")) }
    }

    @Test
    fun limitIsRespected() {
        val engine = KoreanSentenceContinuation()

        val result = engine.continuations(listOf("확인"), ContinuationTone.Honorific, defaultPackage, limit = 2)

        assertEquals(2, result.size)
    }
}
