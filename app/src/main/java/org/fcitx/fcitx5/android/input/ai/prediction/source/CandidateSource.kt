/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 YunChan
 */
package org.fcitx.fcitx5.android.input.ai.prediction.source

import org.fcitx.fcitx5.android.input.ai.AiPrediction
import org.fcitx.fcitx5.android.input.ai.PersonalNgramModel

/**
 * Per-call state shared by every [CandidateSource]: the parsed input, the context with typos in the
 * current sentence already corrected, and lookups more than one source reads.
 */
internal class CandidateRequest(
    val input: PredictionInput,
    val packageName: String,
    val limit: Int,
    val normalizedFullContext: String,
    val isInformal: Boolean,
    val personalNgramCandidates: List<PersonalNgramModel.NgramCandidate>,
    val immediatePredictions: List<AiPrediction>
)

/**
 * One producer of raw candidates. Sources run in a fixed order and their outputs are concatenated
 * before merging, so a source listed earlier wins a duplicate text over a later one.
 */
internal interface CandidateSource {
    fun collect(request: CandidateRequest): List<AiPrediction>
}
