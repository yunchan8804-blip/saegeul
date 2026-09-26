/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 YunChan
 */
package org.fcitx.fcitx5.android.input.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class KoreanDiscourseContinuationTest {
    @Test
    fun `connective endings receive bounded discourse words`() {
        assertEquals(listOf("아직", "생각보다", "그래도"), KoreanDiscourseContinuation.suggest("밥 먹었는데 "))
        assertEquals(listOf("아직", "생각보다", "그래도"), KoreanDiscourseContinuation.suggest("일하고 있는데 "))
        assertEquals(listOf("그래도", "아직"), KoreanDiscourseContinuation.suggest("늦었지만 "))
    }

    @Test
    fun `completed Korean sentence receives a next sentence start with or without a space`() {
        assertEquals(listOf("그리고", "그런데", "이제"), KoreanDiscourseContinuation.suggest("밥 먹었어."))
        assertEquals(listOf("그리고", "그런데", "이제"), KoreanDiscourseContinuation.suggest("밥 먹었어. "))
        assertTrue(KoreanDiscourseContinuation.suggest("버전 1.").isEmpty())
    }

    @Test
    fun `ordinary or incomplete contexts do not invent discourse candidates`() {
        listOf("", "123 ", "!? ", "ㅅ ", "식사 ", "밥 먹었는데\n", "밥 먹었어.\n", "밥 먹었는데\u0000 ").forEach { context ->
            assertTrue(KoreanDiscourseContinuation.suggest(context).isEmpty())
        }
    }

    @Test
    fun `immediate and predictor paths retain the context and one boundary space`() {
        val context = "밥 먹었어. "
        val immediate = ImmediateContextualPredictions.collect(
            input = ImmediateContextualPredictions.Input(context, "com.example", 0L, 5),
            sentencePackLookup = null
        ).first { it.source == "discourse_continuation" }
        assertEquals("이어쓰기", immediate.badge)
        assertFalse(immediate.isSentenceCompletion)
        assertEquals(0.60f, immediate.confidenceScore)
        assertEquals(context, immediate.append?.expectedContext)
        assertEquals(ContextualAppend.JoinMode.NEXT_WORD, immediate.append?.joinMode)
        assertEquals("그리고 ", immediate.append?.insertionFor(context))
        assertEquals(" 그리고 ", immediate.append?.insertionFor("밥 먹었어."))
        assertNull(immediate.append?.insertionFor("밥 먹었어!"))

        val predictor = AiContextualPredictor(ChoseongMorphologyEngine())
        val asyncEquivalent = predictor.predict("", context, "com.example", limit = 8)
            .firstOrNull { it.source == "discourse_continuation" }
        assertEquals("그리고", asyncEquivalent?.text)
        assertEquals(context, asyncEquivalent?.append?.expectedContext)
        assertEquals("그리고 ", asyncEquivalent?.append?.insertionFor(context))
        assertTrue(
            predictor.predict("ㅅ", "밥 먹었는데 ", "com.example", limit = 8)
                .none { it.source == "discourse_continuation" }
        )
    }

    @Test
    fun `predictor keeps a higher priority learned word over matching discourse candidate`() {
        val ngram = PersonalNgramModel()
        ngram.learn("밥 먹었어. 그리고 쉬었어", "com.example")
        val prediction = AiContextualPredictor(ChoseongMorphologyEngine(), ngram = ngram)
            .predict("", "밥 먹었어. ", "com.example", limit = 8)
            .first { it.text == "그리고" && !it.isSentenceCompletion }

        assertEquals("personal_ngram", prediction.source)
        assertTrue(prediction.confidenceScore > 0.60f)
    }
}
