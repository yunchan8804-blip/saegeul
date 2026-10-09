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
 * 사전 위. 문장 시작(첫 어절)에서는 stroke가 있을 때만 문장 시작 분포(`<s>` bigram)로 후보를 낸다.
 */
internal class CorpusNgramSource(
    private val morphology: ChoseongMorphologyEngine,
    private val bundledNgram: () -> BundledKoreanNgram?
) : CandidateSource {

    override fun collect(request: CandidateRequest): List<AiPrediction> {
        val corpusNgram = bundledNgram() ?: return emptyList()
        val typedStroke = request.input.cleanStroke
        // 첫 어절 앞에 붙은 여는 따옴표·괄호는 어절의 일부가 아니므로 매칭에서 뗀다.
        val cleanStroke = typedStroke.trimStart { it in KeyboardTypoCorrectionSource.HEAD_PUNCTUATION }
        if (typedStroke.isNotEmpty() && cleanStroke.isEmpty()) return emptyList()
        val contextWords = BundledKoreanNgram.contextWords(request.input.contextBeforeCursor)
        if (contextWords == null) {
            if (!request.input.isContextKnown || cleanStroke.isBlank() ||
                !BundledKoreanNgram.isSentenceStart(request.input.contextBeforeCursor)
            ) {
                return emptyList()
            }
            return corpusNgram.sentenceStartCompletions(cleanStroke, 3).mapIndexed { idx, candidate ->
                strokeCandidate(candidate.word, idx)
            }
        }
        val (prev2, prev1) = contextWords
        val corpusCandidates = corpusNgram.nextWords(prev2, prev1, 8)
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
            .mapIndexed { idx, candidate -> strokeCandidate(candidate.word, idx) }
    }

    private fun strokeCandidate(word: String, idx: Int) = AiPrediction(
        text = word,
        confidenceScore = 0.957f - idx * 0.005f,
        isSentenceCompletion = false,
        source = "corpus_ngram",
        badge = ""
    )
}
