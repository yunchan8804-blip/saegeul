/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 YunChan
 */
package org.fcitx.fcitx5.android.input.ai.prediction.source

import org.fcitx.fcitx5.android.input.ai.AiPrediction
import org.fcitx.fcitx5.android.input.ai.PersonalNgramModel
import org.fcitx.fcitx5.android.input.ai.typo.BaseKoreanVocabulary

/**
 * Base Korean vocabulary completion (`base_vocab`, bundled TSV), ranked right after the personal
 * n-gram and skipping words that source already proposed.
 */
internal class BaseVocabularySource(
    private val baseVocabulary: BaseKoreanVocabulary?,
    private val ngram: PersonalNgramModel
) : CandidateSource {

    override fun collect(request: CandidateRequest): List<AiPrediction> {
        val cleanStroke = request.input.cleanStroke
        if (cleanStroke.isBlank() || baseVocabulary == null) return emptyList()
        val personalWords = request.personalNgramCandidates.map { it.word }.toSet()
        val completions = baseVocabulary.completions(cleanStroke, 8)
            .filter { (word, _) -> word !in personalWords }
        if (completions.isEmpty()) return emptyList()
        val baseCtxProb = ngram.predictContextualNext(request.input.contextBeforeCursor, request.packageName, 20)
            .associate { it.word to it.probability }
        val vocabCandidates = completions
            .map { (word, prior) -> Triple(word, prior, prior * (1f + 3f * (baseCtxProb[word] ?: 0f))) }
            .sortedByDescending { it.third }
            .take(4)
        return vocabCandidates.mapIndexed { idx, (word, _, _) ->
            val ctxP = baseCtxProb[word] ?: 0f
            AiPrediction(
                text = word,
                confidenceScore = if (ctxP > 0f) 0.955f - idx * 0.005f else 0.93f - idx * 0.005f,
                isSentenceCompletion = false,
                source = "base_vocab",
                badge = ""
            )
        }
    }
}
