/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 YunChan
 */
package org.fcitx.fcitx5.android.input.ai

import org.fcitx.fcitx5.android.input.ai.rag.PersonalGraphStore

/**
 * Re-ranks and filters sentence-line (isSentenceCompletion=true) candidates using purely
 * on-device signals: how much of the candidate is actually new text beyond the typed context,
 * how topically overlapping the candidate is with the recently typed words, and whether the
 * user's own personal n-gram model supports the candidate's next eojeol. No model, network,
 * or I/O is involved.
 */
object SentenceRelevanceReranker {

    private const val RECENT_CONTEXT_MAX_TOKENS = 8
    private const val TOPICAL_OVERLAP_MAX_SHARED = 3
    private const val TOPICAL_OVERLAP_BOOST_PER_TOKEN = 0.12f
    private const val NGRAM_BRIDGE_CANDIDATE_LIMIT = 5
    private const val NGRAM_BRIDGE_MATCH_FACTOR = 1.25f
    private const val NGRAM_BRIDGE_MISMATCH_FACTOR = 0.92f
    private const val NGRAM_BRIDGE_STRONG_PROB_THRESHOLD = 0.15f
    private const val LENGTH_LONG_EOJEOLS_THRESHOLD = 8
    private const val LENGTH_LONG_FACTOR = 0.95f

    // Re-ranking only reweights within an already-bounded confidence range, so clamp the boosted
    // score back into the source scale like the other adjustment layers (AiToneAdaptivePredictor,
    // personalized_style) do, so a near-1.0 candidate cannot exceed 1.0 and dominate the final sort.
    private const val MAX_CONFIDENCE = 0.999f

    fun rerank(
        sentences: List<AiPrediction>,
        contextBeforeCursor: String,
        ngram: PersonalNgramModel?,
        packageName: String,
        limit: Int,
        graphStore: PersonalGraphStore? = null
    ): List<AiPrediction> {
        if (sentences.isEmpty()) return emptyList()

        if (contextBeforeCursor.isBlank()) {
            return sentences.sortedByDescending { it.confidenceScore }.take(limit)
        }

        val recentCtxStems = PersonalNgramTokenizer.tokenize(contextBeforeCursor)
            .takeLast(RECENT_CONTEXT_MAX_TOKENS)
            .map { PersonalNgramTokenizer.stem(it) ?: it }
        // The final context word is usually echoed verbatim by continuation candidates, so it is not
        // evidence of topical overlap; drop it from the overlap set.
        val topicalCtxStems = recentCtxStems.toSet() - listOfNotNull(recentCtxStems.lastOrNull())

        // The graph signal (unlike topical overlap) is not about the echoed candidate word, so it
        // uses the full context stem set including the last token. Loop-invariant like bridgeStems,
        // so resolve once here rather than per candidate.
        val graphCtxStems = PersonalNgramTokenizer.tokenize(contextBeforeCursor)
            .map { PersonalNgramTokenizer.stem(it) ?: it }
            .toSet()

        // predictNext depends only on the (loop-invariant) context, so resolve the personal
        // next-word set once here rather than re-querying the n-gram model per candidate.
        val bridgeCands = ngram?.predictNext(contextBeforeCursor, packageName, NGRAM_BRIDGE_CANDIDATE_LIMIT).orEmpty()
        val bridgeStems = bridgeCands.map { PersonalNgramTokenizer.stem(it.word) ?: it.word }.toSet()
        val bridgeMaxProb = bridgeCands.maxOfOrNull { it.probability } ?: 0f

        val scored = mutableListOf<Pair<AiPrediction, Float>>()
        for (pred in sentences) {
            if (pred.text.trim() == contextBeforeCursor.trim()) continue

            val prefixLen = contextBeforeCursor.commonPrefixWith(pred.text).length
            val rawRemainder = pred.text.substring(prefixLen)
            val remainder = rawRemainder.trim()
            if (remainder.isEmpty()) continue

            val sTokenStems = PersonalNgramTokenizer.tokenize(pred.text)
                .map { PersonalNgramTokenizer.stem(it) ?: it }
                .toSet()
            val shared = (topicalCtxStems intersect sTokenStems).size
            val overlapFactor = 1.0f + minOf(shared, TOPICAL_OVERLAP_MAX_SHARED) * TOPICAL_OVERLAP_BOOST_PER_TOKEN

            val wordBoundary = contextBeforeCursor.endsWith(" ") ||
                contextBeforeCursor.endsWith("\n") ||
                rawRemainder.startsWith(" ")
            val bridgeFactor = if (wordBoundary) {
                bridgeFactorFor(remainder, bridgeStems, bridgeMaxProb)
            } else {
                1.0f
            }

            val newEojeolCount = remainder.split(' ').count { it.isNotBlank() }
            val lengthFactor = if (newEojeolCount > LENGTH_LONG_EOJEOLS_THRESHOLD) LENGTH_LONG_FACTOR else 1.0f

            val graphFactor = graphStore?.proximityBoost(graphCtxStems, sTokenStems) ?: 1.0f

            val newScore = (pred.confidenceScore * overlapFactor * bridgeFactor * lengthFactor * graphFactor)
                .coerceIn(0f, MAX_CONFIDENCE)
            scored.add(pred to newScore)
        }

        return scored
            .sortedByDescending { it.second }
            .take(limit)
            .map { (pred, newScore) -> pred.copy(confidenceScore = newScore) }
    }

    private fun bridgeFactorFor(remainder: String, bridgeStems: Set<String>, bridgeMaxProb: Float): Float {
        if (bridgeStems.isEmpty()) return 1.0f
        val firstNewToken = PersonalNgramTokenizer.tokenize(remainder.substringBefore(' ')).firstOrNull() ?: return 1.0f
        val firstNew = PersonalNgramTokenizer.stem(firstNewToken) ?: firstNewToken
        if (firstNew.isEmpty()) return 1.0f
        return when {
            firstNew in bridgeStems -> NGRAM_BRIDGE_MATCH_FACTOR
            bridgeMaxProb >= NGRAM_BRIDGE_STRONG_PROB_THRESHOLD -> NGRAM_BRIDGE_MISMATCH_FACTOR
            else -> 1.0f
        }
    }
}
