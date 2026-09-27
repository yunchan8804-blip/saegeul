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
