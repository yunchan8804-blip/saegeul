/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 YunChan
 */
package org.fcitx.fcitx5.android.input.ai.prediction.source

import org.fcitx.fcitx5.android.input.ai.AiPrediction
import org.fcitx.fcitx5.android.input.ai.KoreanTypoCorrectionEngine
import org.fcitx.fcitx5.android.input.ai.typo.BaseKoreanVocabulary

/**
 * Legacy-engine corrections of the sentence being typed, plus [normalizedFullContext]: the full
 * context with that sentence (or, failing that, its last word) corrected, which the later sources
 * match against instead of the raw typed text.
 */
internal class SentenceTypoCorrections(val candidates: List<AiPrediction>, val normalizedFullContext: String)

/**
 * Sentence-level typo correction (`typo_sentence_correction`, `typo_word_correction`) by the legacy
 * [KoreanTypoCorrectionEngine], skipping the fragment keyboard-aware correction already handled.
 * The last word of the context is normalized only when it is an explicit typo or not an ordinary
 * base-vocabulary word.
 */
internal class SentenceTypoCorrectionSource(
    private val typoEngine: KoreanTypoCorrectionEngine,
    private val baseVocabulary: BaseKoreanVocabulary?
) {

    fun correct(input: PredictionInput, handledFragment: String?): SentenceTypoCorrections {
        val currentSentence = input.currentSentence
        val candidates = mutableListOf<AiPrediction>()
        var sentenceCorrectedText: String? = null
        if (currentSentence.isNotBlank()) {
            val sentenceTypoPairs = typoEngine.findTypoCorrectionsInSentence(currentSentence)
                .filter { (coreWord, _) -> coreWord != handledFragment }
            if (sentenceTypoPairs.isNotEmpty()) {
                val corrected = typoEngine.correctSentence(currentSentence)
                if (corrected != null && corrected != currentSentence) {
                    sentenceCorrectedText = corrected
                    candidates += AiPrediction(
                        text = corrected,
                        confidenceScore = 0.999f,
                        isSentenceCompletion = corrected.contains(" "),
                        source = "typo_sentence_correction",
                        badge = "✏️"
                    )
                }
                sentenceTypoPairs.forEach { (_, correctedWord) ->
                    candidates += AiPrediction(
                        text = correctedWord,
                        confidenceScore = 0.996f,
                        isSentenceCompletion = correctedWord.contains(" "),
                        source = "typo_word_correction",
                        badge = "✏️"
                    )
                }
            }
        }
        return SentenceTypoCorrections(candidates, normalizedFullContext(input, sentenceCorrectedText))
    }

    private fun normalizedFullContext(input: PredictionInput, sentenceCorrectedText: String?): String {
        val lastWord = input.lastWordInContext
        val correctable = lastWord.isNotBlank() &&
            (typoEngine.hasExplicitTypo(lastWord) || !baseVocabulary.knowsOrdinaryWord(lastWord))
        val correctedLastWord = if (correctable) typoEngine.correct(lastWord).firstOrNull() else null
        return if (sentenceCorrectedText != null && input.currentSentence.isNotBlank()) {
            input.fullContext.replace(input.currentSentence, sentenceCorrectedText)
        } else if (correctedLastWord != null && correctedLastWord != lastWord) {
            val prefix = input.fullContext.dropLast(lastWord.length)
            "$prefix$correctedLastWord".trim()
        } else {
            input.fullContext
        }
    }
}
