/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 YunChan
 */
package org.fcitx.fcitx5.android.input.ai

import org.fcitx.fcitx5.android.input.ai.sentencepack.MatchEvidence
import org.fcitx.fcitx5.android.input.ai.sentencepack.SentencePackMatch

object ImmediateContextualPredictions {

    data class Input(
        val rawContext: String,
        val packageName: String,
        val inputSessionEpoch: Long,
        val limit: Int
    )

    fun collect(
        input: Input,
        sentencePackLookup: ((String, Int) -> List<SentencePackMatch>)?,
        prefetcher: AiSentenceCompletionPrefetcher?
    ): List<AiPrediction> {
        if (input.rawContext.isBlank()) return emptyList()

        val predictions = mutableListOf<AiPrediction>()
        sentencePackLookup?.invoke(input.rawContext, input.limit)?.forEach { match ->
            predictions += AiPrediction(
                text = match.suffix,
                confidenceScore = if (match.evidence == MatchEvidence.LAST_WORD) 0.64f else 0.78f,
                isSentenceCompletion = true,
                source = "sentence_pack",
                badge = "기본문장",
                append = ContextualAppend(input.rawContext, match.suffix, match.joinMode)
            )
        }

        val scope = AiSentenceCompletionPrefetcher.Scope(input.packageName, input.inputSessionEpoch)
        PrefetchedContinuation.parse(
            prefetcher?.getCachedPredictions(input.rawContext, scope).orEmpty(),
            input.rawContext
        ).forEach { proposal ->
            if (
                proposal.kind == PrefetchedContinuation.Kind.CONTINUATION_ATTACH &&
                input.rawContext.lastOrNull()?.isWhitespace() == true
            ) return@forEach
            val isSentence = proposal.kind != PrefetchedContinuation.Kind.WORD
            val joinMode = if (proposal.kind == PrefetchedContinuation.Kind.CONTINUATION_ATTACH) {
                ContextualAppend.JoinMode.ATTACH
            } else {
                ContextualAppend.JoinMode.NEXT_WORD
            }
            predictions += AiPrediction(
                text = proposal.text,
                confidenceScore = if (isSentence) 0.980f else 0.985f,
                isSentenceCompletion = isSentence,
                source = "llm_cached",
                badge = if (isSentence) "✨ AI완성" else "✨ AI단어",
                append = ContextualAppend(input.rawContext, proposal.text, joinMode)
            )
        }

        val seen = mutableSetOf<String>()
        return predictions
            .asSequence()
            .filter { KoreanSuggestionSurface.isDisplayable(it.text) }
            .sortedByDescending { it.confidenceScore }
            .filter { prediction -> seen.add("${prediction.isSentenceCompletion}:${prediction.text}") }
            .toList()
    }
}
