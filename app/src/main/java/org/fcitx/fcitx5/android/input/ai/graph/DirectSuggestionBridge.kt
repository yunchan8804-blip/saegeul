/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 YunChan
 */
package org.fcitx.fcitx5.android.input.ai.graph

import org.fcitx.fcitx5.android.input.ai.PersonalNgramTokenizer

/**
 * Suggestion chip representation ready for immediate zero-latency toolbar rendering.
 */
data class DirectChip(
    val label: String,
    val relation: String,
    val score: Float
)

/**
 * Direct suggestion bridge providing 0ms toolbar suggestion chips by combining
 * real-time keyword/stem extraction from typing context with on-device HippoRAG PPR.
 *
 * Runs completely on-device without any LLM invocations or IPC delays.
 */
class DirectSuggestionBridge(
    private val pprEngine: HippoRagPprEngine,
    private val graphCache: OnDeviceL1GraphCache
) {

    /**
     * Generates direct suggestion chips from the ongoing typing context before the cursor.
     */
    fun suggest(contextBeforeCursor: String, maxChips: Int = 5): List<DirectChip> {
        val seeds = extractSeeds(contextBeforeCursor)
        if (seeds.isEmpty()) return emptyList()
        return suggestFromSeeds(seeds, maxChips)
    }

    /**
     * Generates direct suggestion chips given an explicit set of seed entities/keywords.
     */
    fun suggestFromSeeds(seeds: Set<String>, maxChips: Int = 5): List<DirectChip> {
        val validSeeds = seeds.filter { it.isNotBlank() }.toSet()
        if (validSeeds.isEmpty()) return emptyList()

        val pprResults = pprEngine.computePpr(
            seedEntities = validSeeds,
            maxResults = maxChips,
            excludeSeeds = true
        )

        return pprResults.map { (entityId, score) ->
            val relation = resolveRelation(validSeeds, entityId)
            val label = graphCache.getEntity(entityId)?.label ?: entityId
            DirectChip(
                label = label,
                relation = relation,
                score = score
            )
        }
    }

    /**
     * Extracts seed entities and morphological stems from typing context before the cursor.
     */
    fun extractSeeds(contextBeforeCursor: String): Set<String> {
        if (contextBeforeCursor.isBlank()) return emptySet()
        val seeds = mutableSetOf<String>()

        val tokenized = PersonalNgramTokenizer.tokenize(contextBeforeCursor)
        val candidateTokens = if (tokenized.isNotEmpty()) {
            tokenized.takeLast(MAX_CONTEXT_TOKENS)
        } else {
            contextBeforeCursor.trim()
                .split(WHITESPACE_REGEX)
                .filter { it.isNotBlank() }
                .takeLast(MAX_CONTEXT_TOKENS)
        }

        for (raw in candidateTokens) {
            val token = raw.trim { it in PUNCTUATION_CHARS }
            if (token.isNotBlank()) {
                seeds.add(token)
                val stem = PersonalNgramTokenizer.stem(token)
                if (!stem.isNullOrBlank()) {
                    seeds.add(stem)
                }
            }
        }
        return seeds
    }

    private fun resolveRelation(seedEntities: Set<String>, candidateEntity: String): String {
        for (seed in seedEntities) {
            val edges = graphCache.get1Hop(seed)
            for (edge in edges) {
                if ((edge.src == seed && edge.dst == candidateEntity) ||
                    (edge.dst == seed && edge.src == candidateEntity)
                ) {
                    if (edge.relation.isNotBlank()) {
                        return edge.relation
                    }
                }
            }
        }
        val category = graphCache.getEntity(candidateEntity)?.category
        if (!category.isNullOrBlank()) {
            return category
        }
        return DEFAULT_RELATION
    }

    companion object {
        private const val MAX_CONTEXT_TOKENS = 5
        private const val DEFAULT_RELATION = "연관"
        private val WHITESPACE_REGEX = Regex("\\s+")
        private val PUNCTUATION_CHARS = ".,!?~…\"'()[]{}<>:;。？！、 \t\n\r".toSet()
    }
}
