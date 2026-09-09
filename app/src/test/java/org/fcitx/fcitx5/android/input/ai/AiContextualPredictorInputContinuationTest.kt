/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 YunChan
 */
package org.fcitx.fcitx5.android.input.ai

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AiContextualPredictorInputContinuationTest {

    @Test
    fun independentlyObservedNgramsDoNotBecomeACombinedSentenceCandidate() {
        val prefix = "내가 뭘 해야"
        val separatelyObserved = "뭘 해야 하겠습니다"
        val syntheticCombination = "$prefix 하겠습니다"
        val ngram = PersonalNgramModel(clock = { 1_000_000_000L })
        ngram.learn(prefix, "com.example.test")
        ngram.learn(separatelyObserved, "com.example.test")
        val legacyContinuation = KoreanSentenceContinuation(ngram = ngram)
        assertTrue(
            legacyContinuation.continuations(
                listOf("내가", "뭘"),
                ContinuationTone.Honorific,
                "com.example.test",
                limit = 10
            ).contains(syntheticCombination)
        )
        val predictor = AiContextualPredictor(
            morphology = ChoseongMorphologyEngine(),
            ngram = ngram
        )

        listOf("", "해야").forEach { currentStroke ->
            val results = predictor.predict(
                currentStroke = currentStroke,
                contextBeforeCursor = "내가 뭘 ",
                packageName = "com.example.test",
                limit = 10
            )

            assertFalse(results.any { it.isSentenceCompletion && it.text == syntheticCombination })
        }
    }

    @Test
    fun coldHadaContextDoesNotSynthesizeASentenceCandidate() {
        val predictor = AiContextualPredictor(
            morphology = ChoseongMorphologyEngine(),
            ngram = PersonalNgramModel(clock = { 1_000_000_000L })
        )

        val results = predictor.predict(
            currentStroke = "확인",
            contextBeforeCursor = "",
            packageName = "com.example.test",
            limit = 10
        )

        assertFalse(results.any { it.isSentenceCompletion && it.text == "확인하겠습니다" })
    }
}
