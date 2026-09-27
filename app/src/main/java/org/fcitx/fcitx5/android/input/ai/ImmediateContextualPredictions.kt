/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 YunChan
 */
package org.fcitx.fcitx5.android.input.ai

import org.fcitx.fcitx5.android.input.ai.persona.PersonaRegistry
import org.fcitx.fcitx5.android.input.ai.rule.SuggestionQualityGate
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
        generatedSentenceLookup: ((String, Int) -> List<SentencePackMatch>)? = null,
        generatedSpacingLookup: ((String) -> String?)? = null
    ): List<AiPrediction> {
        if (input.rawContext.isBlank()) return emptyList()

        val predictions = mutableListOf<AiPrediction>()
        KoreanDiscourseContinuation.suggest(input.rawContext).forEachIndexed { index, word ->
            predictions += AiPrediction(
                text = word,
                confidenceScore = 0.60f - index * 0.01f,
                source = "discourse_continuation",
                badge = "이어쓰기",
                append = ContextualAppend(input.rawContext, word)
            )
        }
        val trimmedContext = input.rawContext.trim(' ')
        generatedSpacingLookup?.invoke(trimmedContext)
            ?.takeIf { target ->
                trimmedContext.isNotBlank() && target.isNotBlank() && target != trimmedContext
            }
            ?.let { target ->
                val leadingSpaces = input.rawContext.takeWhile { it == ' ' }
                val trailingSpaces = input.rawContext.takeLastWhile { it == ' ' }
                val replacement = "$leadingSpaces$target$trailingSpaces"
                predictions += AiPrediction(
                    text = replacement,
                    confidenceScore = 0.84f,
                    isSentenceCompletion = true,
                    source = "ondevice_generated_spacing",
                    badge = "기기 AI 띄어쓰기",
                    replacement = ContextualReplacement(
                        expectedContext = input.rawContext,
                        replacement = replacement
                    )
                )
            }
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

        val persona = PersonaRegistry.classify(input.packageName)
        if (persona != "browser" && persona != "commerce") {
            generatedSentenceLookup?.invoke(input.rawContext, input.limit)
                ?.filter { it.evidence == MatchEvidence.PREFIX || it.evidence == MatchEvidence.CONTEXT_SUFFIX }
                ?.forEach { match ->
                    predictions += AiPrediction(
                        text = match.suffix,
                        confidenceScore = 0.70f,
                        isSentenceCompletion = true,
                        source = "ondevice_generated",
                        badge = "기기 AI 재료",
                        append = ContextualAppend(input.rawContext, match.suffix, match.joinMode)
                    )
                }
        }

        val seen = mutableSetOf<String>()
        return predictions
            .asSequence()
            .filter { KoreanSuggestionSurface.isDisplayable(it.text) }
            // 최종 품질 게이트: 여기 한 곳에서 이 함수가 만든 모든 후보(문장팩·기기 AI 재료·
            // 띄어쓰기 교정·이어쓰기)를 정본 SuggestionQualityGate로 거른다.
            .filter { SuggestionQualityGate.accepts(it.text, input.rawContext, it.isSentenceCompletion) }
            .sortedByDescending { it.confidenceScore }
            .filter { prediction -> seen.add("${prediction.isSentenceCompletion}:${prediction.text}") }
            .toList()
    }
}
