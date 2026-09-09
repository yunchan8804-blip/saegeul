/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 YunChan
 */
package org.fcitx.fcitx5.android.input.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class AiContextualPredictorPrefetchedContinuationTest {
    @Test
    fun `cached typed proposals preserve their word and continuation kinds`() {
        val context = "회의"
        val prefetcher = AiSentenceCompletionPrefetcher()
        prefetcher.putPredictions(
            context,
            listOf(
                "WORD\t다음",
                "CONTINUATION\t준비가 필요해",
                "CONTINUATION_ATTACH\t에 참석해 주세요."
            ),
            AiSentenceCompletionPrefetcher.Scope("com.example.test", 0L)
        )
        val predictor = AiContextualPredictor(
            morphology = ChoseongMorphologyEngine(),
            prefetcher = prefetcher
        )

        val predictions = predictor.predict("", context, "com.example.test", limit = 8)
        val word = predictions.firstOrNull { it.source == "llm_cached" && it.text == "다음" }
        val continuation = predictions.firstOrNull {
            it.source == "llm_cached" && it.text == "준비가 필요해"
        }
        val attachment = predictions.firstOrNull {
            it.source == "llm_cached" && it.text == "에 참석해 주세요."
        }

        assertNotNull(word)
        assertEquals(false, word!!.isSentenceCompletion)
        assertEquals(ContextualAppend(context, "다음"), word.append)
        assertNotNull(continuation)
        assertEquals(true, continuation!!.isSentenceCompletion)
        assertEquals(ContextualAppend(context, "준비가 필요해"), continuation.append)
        assertNotNull(attachment)
        assertEquals(true, attachment!!.isSentenceCompletion)
        assertEquals(
            ContextualAppend(context, "에 참석해 주세요.", ContextualAppend.JoinMode.ATTACH),
            attachment.append
        )
    }

    @Test
    fun `cached attachment is hidden when the input already ends with whitespace`() {
        val context = "회의 "
        val prefetcher = AiSentenceCompletionPrefetcher()
        prefetcher.putPredictions(
            context,
            listOf("CONTINUATION_ATTACH\t에 참석해 주세요."),
            AiSentenceCompletionPrefetcher.Scope("com.example.test", 0L)
        )
        val predictor = AiContextualPredictor(
            morphology = ChoseongMorphologyEngine(),
            prefetcher = prefetcher
        )

        val predictions = predictor.predict("", context, "com.example.test", limit = 8)

        assertEquals(null, predictions.firstOrNull { it.source == "llm_cached" })
    }
}
