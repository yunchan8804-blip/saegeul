/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 YunChan
 */
package org.fcitx.fcitx5.android.input.ai.prediction.source

import org.fcitx.fcitx5.android.input.ai.AiPrediction
import org.fcitx.fcitx5.android.input.ai.BundledKoreanNgram
import org.fcitx.fcitx5.android.input.ai.ChoseongMorphologyEngine

/**
 * Bundled corpus n-gram (`corpus_ngram`, FineWeb-2 어절 bigram/trigram): 개인 n-gram 아래, 문맥 없는
 * 사전 위.
 */
internal class CorpusNgramSource(
    private val morphology: ChoseongMorphologyEngine,
    private val bundledNgram: () -> BundledKoreanNgram?
) : CandidateSource {

    override fun collect(request: CandidateRequest): List<AiPrediction> {
        val corpusNgram = bundledNgram() ?: return emptyList()
        val (prev2, prev1) = BundledKoreanNgram.contextWords(request.input.contextBeforeCursor) ?: return emptyList()
        val corpusCandidates = corpusNgram.nextWords(prev2, prev1, 8)
        val cleanStroke = request.input.cleanStroke
        if (cleanStroke.isBlank()) {
            return corpusCandidates.take(4).mapIndexed { idx, candidate ->
                val base = if (candidate.order == 3) 0.93f else 0.90f
                AiPrediction(
                    text = candidate.word,
                    confidenceScore = (base - idx * 0.01f).coerceAtLeast(0.84f),
                    isSentenceCompletion = false,
                    source = "corpus_ngram",
                    badge = ""
                )
            }
        }
        val strokeJamo = morphology.decomposeHangul(cleanStroke)
        val choseongOnly = cleanStroke.all { morphology.isChoseong(it) }
        return corpusCandidates
            .filter { candidate ->
                candidate.word.startsWith(cleanStroke) ||
                    morphology.decomposeHangul(candidate.word).startsWith(strokeJamo) ||
                    (choseongOnly && morphology.extractChoseongSequence(candidate.word).startsWith(cleanStroke))
            }
            .take(3)
            .mapIndexed { idx, candidate ->
                AiPrediction(
                    text = candidate.word,
                    confidenceScore = 0.957f - idx * 0.005f,
                    isSentenceCompletion = false,
                    source = "corpus_ngram",
                    badge = ""
                )
            }
    }
}
