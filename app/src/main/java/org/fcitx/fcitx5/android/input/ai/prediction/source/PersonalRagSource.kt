/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 YunChan
 */
package org.fcitx.fcitx5.android.input.ai.prediction.source

import org.fcitx.fcitx5.android.input.ai.AiPrediction
import org.fcitx.fcitx5.android.input.ai.PersonalSentenceCompletionGate
import org.fcitx.fcitx5.android.input.ai.rag.PersonalSentenceVault

/** Personal sentence RAG (`rag_personal`): on-device BM25 search over the user's own past sentences. */
internal class PersonalRagSource(private val personalSentenceVault: PersonalSentenceVault?) : CandidateSource {

    override fun collect(request: CandidateRequest): List<AiPrediction> {
        val vault = personalSentenceVault ?: return emptyList()
        val context = request.normalizedFullContext
        if (context.isBlank()) return emptyList()
        val ragMatches = vault.retrieve(context, request.packageName, request.limit)
            .filter { PersonalSentenceCompletionGate.isContinuation(context, it.sentence) }
        // retrieve() returns matches sorted by an unbounded BM25-derived magnitude. Map that
        // magnitude into a fixed [0.90, 0.95] band relative to the top match so a strongly
        // relevant sentence keeps its lead over a weakly relevant one (instead of collapsing the
        // gap to a flat per-index step), while staying in a predictable range beside other sources.
        val topRagScore = ragMatches.firstOrNull()?.score ?: 0f
        return ragMatches.map { retrieved ->
            val relative = if (topRagScore > 0f) (retrieved.score / topRagScore).coerceIn(0f, 1f) else 0f
            var score = 0.90f + 0.05f * relative
            if (retrieved.startsWithLastWord) score += 0.02f
            AiPrediction(
                text = retrieved.sentence,
                confidenceScore = score.coerceAtMost(0.999f),
                isSentenceCompletion = true,
                source = "rag_personal",
                badge = "내 기록"
            )
        }
    }
}
