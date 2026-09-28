/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 YunChan
 */
package org.fcitx.fcitx5.android.input.ai.prediction.source

import org.fcitx.fcitx5.android.input.ai.AiPrediction

/** Personal n-gram context predictions (`personal_ngram`) from [CandidateRequest.personalNgramCandidates]. */
internal class PersonalNgramSource : CandidateSource {

    override fun collect(request: CandidateRequest): List<AiPrediction> =
        request.personalNgramCandidates.mapIndexed { idx, candidate ->
            val score = when {
                candidate.evidence >= 2f -> 0.99f - idx * 0.005f
                candidate.evidence >= 1f -> 0.975f - idx * 0.005f
                else -> 0.94f - idx * 0.005f
            }
            AiPrediction(
                text = candidate.word,
                confidenceScore = score,
                isSentenceCompletion = false,
                source = "personal_ngram",
                badge = "⭐"
            )
        }
}
