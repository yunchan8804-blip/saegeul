/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 YunChan
 */
package org.fcitx.fcitx5.android.input.ai.ondevice.gemma

import org.fcitx.fcitx5.android.input.ai.ondevice.OnDeviceSharedEngine
import org.fcitx.fcitx5.android.input.ai.rag.PersonalGraphEnricher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GemmaGraphPromptTest {

    @Test
    fun budgetIsTheContextMinusPromptTextOutputReserveAndMargin() {
        val fixedPromptTokens = GemmaGraphPrompt.estimatedTokens(
            GemmaGraphPrompt.build(PersonalGraphEnricher.ENRICHMENT_INSTRUCTION, "")
        )

        assertEquals(
            OnDeviceSharedEngine.MAX_NUM_TOKENS - fixedPromptTokens - 512 - 64,
            GemmaGraphPrompt.maxChunkChars()
        )
        assertTrue(GemmaGraphPrompt.OUTPUT_RESERVE_TOKENS >= 512)
    }

    @Test
    fun aChunkAtTheBudgetLeavesTheOutputReserveAndOneMoreCharacterDoesNot() {
        val budget = GemmaGraphPrompt.maxChunkChars()
        val reserved = GemmaGraphPrompt.OUTPUT_RESERVE_TOKENS + GemmaGraphPrompt.SAFETY_MARGIN_TOKENS
        fun promptTokens(chunkChars: Int) = GemmaGraphPrompt.estimatedTokens(
            GemmaGraphPrompt.build(PersonalGraphEnricher.ENRICHMENT_INSTRUCTION, "가".repeat(chunkChars))
        )

        assertTrue(promptTokens(budget) + reserved <= OnDeviceSharedEngine.MAX_NUM_TOKENS)
        assertTrue(promptTokens(budget + 1) + reserved > OnDeviceSharedEngine.MAX_NUM_TOKENS)
    }

    @Test
    fun budgetNeverDropsBelowOneCharacter() {
        assertEquals(1, GemmaGraphPrompt.maxChunkChars(contextTokens = 100))
    }

    @Test
    fun chunksPackedToTheBudgetKeepEveryPromptInsideTheContext() {
        val budget = GemmaGraphPrompt.maxChunkChars()
        val sentences = listOf("가".repeat(budget * 3)) + (0 until 50).map { "짧은 문장 ${(0xAC00 + it).toChar()}" }

        val chunks = PersonalGraphEnricher.chunksFor(sentences, budget)

        chunks.forEach { chunk ->
            val tokens = GemmaGraphPrompt.estimatedTokens(
                GemmaGraphPrompt.build(PersonalGraphEnricher.ENRICHMENT_INSTRUCTION, chunk)
            )
            assertTrue(tokens + GemmaGraphPrompt.OUTPUT_RESERVE_TOKENS + GemmaGraphPrompt.SAFETY_MARGIN_TOKENS <= OnDeviceSharedEngine.MAX_NUM_TOKENS)
        }
        assertEquals("가".repeat(budget), chunks.first())
    }
}
