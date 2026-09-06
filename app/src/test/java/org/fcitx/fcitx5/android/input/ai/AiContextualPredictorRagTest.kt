/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 YunChan
 */
package org.fcitx.fcitx5.android.input.ai

import org.fcitx.fcitx5.android.input.ai.rag.PersonalSentenceVault
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Verifies that AiContextualPredictor surfaces sentences retrieved from the user's on-device
 * [PersonalSentenceVault] as rag_personal sentence-line candidates, and that leaving the vault
 * unset (the default) leaves existing predict() behavior unchanged.
 */
class AiContextualPredictorRagTest {

    @Test
    fun surfacesRecordedSentenceAsRagPersonalCandidate() {
        val vault = PersonalSentenceVault(clock = { 1000L })
        vault.record("오늘 회의 참석하겠습니다", "com.example.test")

        val predictor = AiContextualPredictor(
            morphology = ChoseongMorphologyEngine(),
            ngram = PersonalNgramModel(),
            personalSentenceVault = vault
        )

        val results = predictor.predict(
            currentStroke = "",
            contextBeforeCursor = "오늘 회의",
            packageName = "com.example.test",
            limit = 5
        )

        val match = results.find { it.source == "rag_personal" && it.text == "오늘 회의 참석하겠습니다" }
        assertTrue(match != null)
        assertTrue(match!!.isSentenceCompletion)
    }

    @Test
    fun withoutVaultProducesNoRagPersonalCandidates() {
        val predictor = AiContextualPredictor(
            morphology = ChoseongMorphologyEngine(),
            ngram = PersonalNgramModel()
        )

        val results = predictor.predict(
            currentStroke = "",
            contextBeforeCursor = "오늘 회의",
            packageName = "com.example.test",
            limit = 5
        )

        assertFalse(results.any { it.source == "rag_personal" })
    }
}
