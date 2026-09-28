/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 YunChan
 */
package org.fcitx.fcitx5.android.input.ai.prediction.source

import org.fcitx.fcitx5.android.input.ai.AiPrediction
import org.fcitx.fcitx5.android.input.ai.PersonalSentenceCompletionGate
import org.fcitx.fcitx5.android.input.ai.PersonalizedSentenceRecord
import org.fcitx.fcitx5.android.input.ai.PersonalizedSentenceStore
import org.fcitx.fcitx5.android.input.ai.rule.KoreanSyntaxRuleFilter

/**
 * Personalized learned sentences (`personalized_style`, highest priority). Only the user's own
 * previously typed sentences (SOURCE_USER_PHRASE) reach the sentence line — synthetic/LLM-authored
 * records are content templates, not something the user actually typed, so they are excluded here
 * to keep the sentence line entirely user-data-driven.
 */
internal class PersonalizedStyleSource(private val personalizedStore: PersonalizedSentenceStore?) : CandidateSource {

    override fun collect(request: CandidateRequest): List<AiPrediction> {
        val store = personalizedStore ?: return emptyList()
        val cleanStroke = request.input.cleanStroke
        val context = request.normalizedFullContext
        if (cleanStroke.isBlank() && context.isBlank()) return emptyList()
        return store.query(
            queryChoseong = cleanStroke,
            context = context,
            limit = request.limit
        ).filter {
            it.source == PersonalizedSentenceRecord.SOURCE_USER_PHRASE &&
                PersonalSentenceCompletionGate.isContinuation(context, it.sentence) &&
                KoreanSyntaxRuleFilter.isGrammaticallySound(it.sentence, context)
        }.map { record ->
            AiPrediction(
                text = record.sentence,
                confidenceScore = (0.96f + (record.score * 0.01f)).coerceAtMost(0.999f),
                isSentenceCompletion = true,
                source = "personalized_style",
                badge = "내 스타일"
            )
        }
    }
}
