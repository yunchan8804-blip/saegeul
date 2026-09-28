/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 YunChan
 */
package org.fcitx.fcitx5.android.input.ai.prediction.source

import org.fcitx.fcitx5.android.input.ai.AiPrediction
import org.fcitx.fcitx5.android.input.ai.ChoseongMorphologyEngine
import org.fcitx.fcitx5.android.input.ai.KoreanCollocationModel

/** Korean collocation and next-word transition model (`collocation_next_word`). */
internal class CollocationSource(
    private val morphology: ChoseongMorphologyEngine,
    private val collocationModel: KoreanCollocationModel
) : CandidateSource {

    override fun collect(request: CandidateRequest): List<AiPrediction> {
        val effectiveLastWord = request.input.effectiveLastWord
        if (effectiveLastWord.isBlank()) return emptyList()
        val cleanStroke = request.input.cleanStroke
        val limit = request.limit
        val nextWords = collocationModel.predictNextWords(effectiveLastWord, request.isInformal, limit = limit * 2)
        // 사용자에게서 학습한 bigram은 개인 데이터라 높게 두고, 코드에 박아 둔 정적 연어는
        // 코퍼스 n-gram 아래로 내려 그 후보가 없는 문맥만 채우게 한다.
        val learnedNextWords = collocationModel
            .predictLearnedNextWords(effectiveLastWord, request.isInformal, limit = limit * 2)
            .toSet()
        return nextWords.mapIndexedNotNull { idx, nextWord ->
            val matches = if (cleanStroke.isBlank()) {
                true
            } else {
                nextWord.startsWith(cleanStroke) || morphology.matchesChoseong(nextWord, cleanStroke)
            }
            if (!matches) return@mapIndexedNotNull null
            val score = if (nextWord in learnedNextWords) {
                (0.95f - (idx * 0.01f)).coerceAtLeast(0.85f)
            } else {
                (0.83f - (idx * 0.01f)).coerceAtLeast(0.75f)
            }
            val isSentence = nextWord.contains(" ") && nextWord.length > 8
            AiPrediction(
                text = nextWord,
                confidenceScore = score,
                isSentenceCompletion = isSentence,
                source = "collocation_next_word",
                badge = if (isSentence) "AI 완성" else "AI 단어"
            )
        }
    }
}
