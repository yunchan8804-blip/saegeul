/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 YunChan
 */
package org.fcitx.fcitx5.android.input.ai.prediction.source

import org.fcitx.fcitx5.android.input.ai.AiPrediction
import org.fcitx.fcitx5.android.input.ai.KoreanTypoCorrectionEngine

/**
 * Word-level typo correction (`typo_correction`) by the legacy [KoreanTypoCorrectionEngine] for the
 * stroke and the word before the cursor, skipping the fragment keyboard-aware correction handled
 * and the words of a sentence that already ended.
 */
internal class WordTypoCorrectionSource(private val typoEngine: KoreanTypoCorrectionEngine) {

    fun collect(input: PredictionInput, handledFragment: String?): List<AiPrediction> {
        val cleanStroke = input.cleanStroke
        val lastWordInContext = input.lastWordInContext
        val typoCandidates = mutableListOf<String>()
        if (cleanStroke.isNotBlank()) {
            typoCandidates.add(cleanStroke)
        }
        if (lastWordInContext.isNotBlank() && !input.contextEndsSentence) {
            if (!input.hasTrailingSpace) {
                typoCandidates.add(lastWordInContext)
                if (cleanStroke.isNotBlank() && !lastWordInContext.endsWith(cleanStroke)) {
                    typoCandidates.add("$lastWordInContext$cleanStroke")
                }
            } else if (typoEngine.hasExplicitTypo(lastWordInContext)) {
                // If trailing whitespace exists after the word, only correct explicit known typos
                // (e.g. "시프지 ", "오눌 ") to avoid false-positive fuzzy matches on normal words
                typoCandidates.add(lastWordInContext)
            }
        }

        return typoCandidates.distinct()
            .filter { it != handledFragment }
            .flatMap { candidateWord ->
                typoEngine.correct(candidateWord).map { correctedWord ->
                    AiPrediction(
                        text = correctedWord,
                        confidenceScore = 0.995f,
                        isSentenceCompletion = correctedWord.contains(" "),
                        source = "typo_correction",
                        badge = "✏️"
                    )
                }
            }
    }
}
