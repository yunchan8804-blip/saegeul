/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 YunChan
 */
package org.fcitx.fcitx5.android.input.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * TDD Test ensuring that generic/repetitive '추천' badges and comments
 * are completely eliminated from word and sentence candidates.
 */
class RecommendationBadgeEliminationTest {

    private lateinit var ngram: PersonalNgramModel
    private lateinit var morphology: ChoseongMorphologyEngine
    private lateinit var predictor: AiContextualPredictor

    @Before
    fun setUp() {
        ngram = PersonalNgramModel()
        morphology = ChoseongMorphologyEngine()
        predictor = AiContextualPredictor(
            morphology = morphology,
            semanticPredictor = KoreanSemanticSentencePredictor(),
            prefetcher = null,
            personalizedStore = null,
            ngram = ngram
        )
    }

    @Test
    fun `predict with empty context should NOT have any candidate with badge 추천`() {
        val results = predictor.predict(
            currentStroke = "",
            contextBeforeCursor = "",
            packageName = "com.test.app",
            limit = 10
        )

        results.forEach { prediction ->
            assertFalse(
                "Prediction '${prediction.text}' must not have badge '추천'",
                prediction.badge == "추천" || prediction.badge.contains("추천")
            )
        }
    }

    @Test
    fun `predict with choseong matching should NOT produce badge 추천`() {
        val results = predictor.predict(
            currentStroke = "ㅇㄴ",
            contextBeforeCursor = "",
            packageName = "com.test.app",
            limit = 5
        )

        assertTrue("Should match '안녕하세요'", results.any { it.text.startsWith("안녕하세요") })
        results.forEach { prediction ->
            assertFalse(
                "Prediction '${prediction.text}' must not have badge '추천'",
                prediction.badge == "추천"
            )
        }
    }

    @Test
    fun `word candidates should have empty badge or domain specific badge but never 추천`() {
        ngram.learn("내일 만나요", "com.test.app")
        ngram.learn("내일 만나요", "com.test.app")

        val results = predictor.predict(
            currentStroke = "",
            contextBeforeCursor = "내일",
            packageName = "com.test.app",
            limit = 5
        )

        val wordCandidates = results.filter { !it.isSentenceCompletion }
        wordCandidates.forEach { word ->
            assertFalse("Word candidate must not have badge '추천'", word.badge == "추천")
        }
    }
}
