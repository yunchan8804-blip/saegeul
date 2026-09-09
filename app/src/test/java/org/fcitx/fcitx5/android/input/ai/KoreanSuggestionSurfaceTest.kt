/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 YunChan
 */
package org.fcitx.fcitx5.android.input.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KoreanSuggestionSurfaceTest {

    private val packageName = "com.example.test"

    @Test
    fun displayabilityRejectsIsolatedCompatibilityJamoButPreservesNormalText() {
        listOf("", "   ", "ㄴㅎ", "고마워 ㄴ").forEach {
            assertFalse(KoreanSuggestionSurface.isDisplayable(it))
        }
        listOf("먼가", "즐거웠오", "ㅋㅋ고마워ㅋㅋ", "hello 고마워ㅋㅋ", "🙂 고마워", "고마워 ㅋㅋ", "ㅎ", "ㅠ", "ㅜ").forEach {
            assertTrue(KoreanSuggestionSurface.isDisplayable(it))
        }
    }

    @Test
    fun ngramFiltersNoisyCandidatesBeforeApplyingLimitsWithoutChangingLearning() {
        val model = PersonalNgramModel(clock = { 1_000_000_000L })
        repeat(3) { model.learn("문맥 ㄴㅎ", packageName) }
        model.learn("문맥 나중", packageName)

        assertTrue(model.unigramCount("ㄴㅎ") > 0f)
        assertEquals("나중", model.predictNext("문맥", packageName, 1).single().word)
        assertEquals("나중", model.predictContextualNext("문맥", packageName, 1).single().word)
        assertEquals("나중", model.complete("ㄴ", "문맥", packageName, 1).single().word)
    }

    @Test
    fun predictorGuardFiltersNoisyPersonalSentenceCandidates() {
        val store = PersonalizedSentenceStore()
        store.upsert(
            PersonalizedSentenceRecord(
                sentence = "오늘 ㄴㅎ",
                source = PersonalizedSentenceRecord.SOURCE_USER_PHRASE,
                score = 1f
            )
        )
        val predictor = AiContextualPredictor(
            morphology = ChoseongMorphologyEngine(),
            personalizedStore = store,
            ngram = PersonalNgramModel(clock = { 1_000_000_000L })
        )

        val results = predictor.predict(
            currentStroke = "",
            contextBeforeCursor = "오늘",
            packageName = packageName,
            limit = 5
        )

        assertFalse(results.any { it.text == "오늘 ㄴㅎ" })
    }
}
