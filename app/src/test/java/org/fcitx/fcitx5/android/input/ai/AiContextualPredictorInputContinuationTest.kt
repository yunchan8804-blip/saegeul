/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 YunChan
 */
package org.fcitx.fcitx5.android.input.ai

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Verifies that when the input-preserving [KoreanSentenceContinuation] source produces a
 * result, AiContextualPredictor suppresses the unrelated fixed-template semantic_sentence
 * source for the same predict() call.
 */
class AiContextualPredictorInputContinuationTest {

    @Test
    fun inputContinuationSuppressesSemanticTemplateWhenItProducesResults() {
        val predictor = AiContextualPredictor(
            morphology = ChoseongMorphologyEngine(),
            ngram = PersonalNgramModel()
        )

        val results = predictor.predict(
            currentStroke = "참석",
            contextBeforeCursor = "회의 ",
            packageName = "com.example.test",
            limit = 5
        )

        assertTrue(results.any { it.source == "input_continuation" && it.text == "회의 참석하겠습니다" })
        assertTrue(results.none { it.source == "semantic_sentence" })
    }
}
