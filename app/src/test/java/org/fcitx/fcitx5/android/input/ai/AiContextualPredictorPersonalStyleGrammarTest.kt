/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 YunChan
 */
package org.fcitx.fcitx5.android.input.ai

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression test for the "안녕하해요" bug: an ungrammatical sentence that was previously stored
 * as a SOURCE_USER_PHRASE record (e.g. injected before the ACC-05 grammar gate existed, or by a
 * corrupted vault entry) must never surface on the personalized_style sentence line, even though
 * it still passes [PersonalSentenceCompletionGate] as a plausible continuation.
 */
class AiContextualPredictorPersonalStyleGrammarTest {

    @Test
    fun ungrammaticalStoredUserPhraseIsNotSurfacedAsPersonalizedStyle() {
        val store = PersonalizedSentenceStore()
        store.upsert(
            PersonalizedSentenceRecord(
                sentence = "안녕하해요",
                source = PersonalizedSentenceRecord.SOURCE_USER_PHRASE,
                score = 5.0f,
                useCount = 3
            )
        )

        val predictor = AiContextualPredictor(
            morphology = ChoseongMorphologyEngine(),
            personalizedStore = store,
            ngram = PersonalNgramModel(clock = { 1_000_000_000L })
        )

        val results = predictor.predict(
            currentStroke = "안녕",
            contextBeforeCursor = "",
            packageName = "com.kakao.talk",
            limit = 10
        )

        assertFalse(
            "'안녕하해요' must never reach a sentence candidate: $results",
            results.any { it.text == "안녕하해요" }
        )
        assertFalse(results.any { it.source == "personalized_style" && it.text == "안녕하해요" })
    }

    @Test
    fun grammaticalStoredUserPhraseIsStillSurfacedAsPersonalizedStyle() {
        val store = PersonalizedSentenceStore()
        store.upsert(
            PersonalizedSentenceRecord(
                sentence = "안녕하세요 반갑습니다",
                source = PersonalizedSentenceRecord.SOURCE_USER_PHRASE,
                score = 5.0f,
                useCount = 3
            )
        )

        val predictor = AiContextualPredictor(
            morphology = ChoseongMorphologyEngine(),
            personalizedStore = store,
            ngram = PersonalNgramModel(clock = { 1_000_000_000L })
        )

        val results = predictor.predict(
            currentStroke = "안녕",
            contextBeforeCursor = "",
            packageName = "com.kakao.talk",
            limit = 10
        )

        assertTrue(
            "A grammatically sound stored phrase must still be offered: $results",
            results.any { it.source == "personalized_style" && it.text == "안녕하세요 반갑습니다" }
        )
    }
}
