/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 YunChan
 */
package org.fcitx.fcitx5.android.input.ai.ondevice.gemma

import org.fcitx.fcitx5.android.input.ai.ondevice.OnDeviceSharedEngine
import org.fcitx.fcitx5.android.input.ai.rag.PersonalGraphEnricher

/**
 * The prompt [GemmaGraphEnrichmentWorker] sends per chunk, and how much sentence text fits in it.
 *
 * The engine's context ([OnDeviceSharedEngine.MAX_NUM_TOKENS]) holds the prompt and the generated JSON
 * together, and LiteRT-LM exposes no tokenizer to count a prompt up front (only a count after the
 * fact), so tokens are estimated conservatively as one per character, Korean or mixed text alike.
 * The room for sentences is therefore the context minus the fixed prompt text, the room reserved for
 * the generated JSON ([OUTPUT_RESERVE_TOKENS]) and a margin ([SAFETY_MARGIN_TOKENS]).
 */
internal object GemmaGraphPrompt {
    const val OUTPUT_RESERVE_TOKENS = 512
    const val SAFETY_MARGIN_TOKENS = 64

    fun build(instruction: String, chunk: String): String = """
        $instruction
        JSON 객체 하나만 출력하고, 설명이나 코드펜스는 절대 포함하지 마라.
        노드는 최대 20개, 엣지는 최대 25개, 토픽은 최대 3개까지만 만들어라.
        ---BEGIN SENTENCES---
        $chunk
        ---END SENTENCES---
    """.trimIndent()

    fun estimatedTokens(text: String): Int = text.length

    fun maxChunkChars(
        contextTokens: Int = OnDeviceSharedEngine.MAX_NUM_TOKENS,
        instruction: String = PersonalGraphEnricher.ENRICHMENT_INSTRUCTION
    ): Int = (
        contextTokens - estimatedTokens(build(instruction, "")) - OUTPUT_RESERVE_TOKENS - SAFETY_MARGIN_TOKENS
        ).coerceAtLeast(1)
}
