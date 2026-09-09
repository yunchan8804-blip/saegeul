/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 YunChan
 */
package org.fcitx.fcitx5.android.input.ai

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ContextualPredictionRelevanceRegressionTest {

    private val packageName = "com.example.test"

    @Test
    fun blankStrokeDoesNotLeakUnrelatedMeetingWordsOrSentence() {
        val ngram = PersonalNgramModel(clock = { 1_000_000_000L })
        repeat(20) { ngram.learn("오늘 회의 참석합니다", packageName) }
        val predictor = AiContextualPredictor(
            morphology = ChoseongMorphologyEngine(),
            ngram = ngram
        )

        val results = predictor.predict(
            currentStroke = "",
            contextBeforeCursor = "내가 뭘 ",
            packageName = packageName,
            limit = 5
        )

        assertFalse(results.any { prediction ->
            listOf("오늘", "회의", "참석").any(prediction.text::contains)
        })
    }

    @Test
    fun blankStrokeWithoutTrailingSpaceDoesNotBuildUnrelatedMeetingSentence() {
        val ngram = PersonalNgramModel(clock = { 1_000_000_000L })
        repeat(20) { ngram.learn("오늘 회의 참석합니다", packageName) }
        val predictor = AiContextualPredictor(
            morphology = ChoseongMorphologyEngine(),
            ngram = ngram
        )

        val results = predictor.predict(
            currentStroke = "",
            contextBeforeCursor = "내가 뭘",
            packageName = packageName,
            limit = 5
        )

        assertFalse(results.any {
            it.isSentenceCompletion && (it.text.contains("회의") || it.text.contains("참석"))
        })
    }

    @Test
    fun activeStrokeDoesNotBuildUnrelatedMeetingSentence() {
        val ngram = PersonalNgramModel(clock = { 1_000_000_000L })
        repeat(20) { ngram.learn("오늘 회의 참석합니다", packageName) }
        val predictor = AiContextualPredictor(
            morphology = ChoseongMorphologyEngine(),
            ngram = ngram
        )

        val results = predictor.predict(
            currentStroke = "뭘",
            contextBeforeCursor = "내가 ",
            packageName = packageName,
            limit = 5
        )

        assertFalse(results.any {
            it.isSentenceCompletion && (it.text.contains("회의") || it.text.contains("참석"))
        })
    }

    @Test
    fun blankStrokeKeepsObservedNextWord() {
        val ngram = PersonalNgramModel(clock = { 1_000_000_000L })
        ngram.learn("내가 뭘 잘못 했어", packageName)
        val predictor = AiContextualPredictor(
            morphology = ChoseongMorphologyEngine(),
            ngram = ngram
        )

        val results = predictor.predict(
            currentStroke = "",
            contextBeforeCursor = "내가 뭘 ",
            packageName = packageName,
            limit = 5
        )

        assertTrue(results.any { !it.isSentenceCompletion && it.text == "잘못" && it.source == "personal_ngram" })
    }
}
