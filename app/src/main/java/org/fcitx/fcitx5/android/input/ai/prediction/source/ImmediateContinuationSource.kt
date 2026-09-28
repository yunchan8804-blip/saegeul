/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 YunChan
 */
package org.fcitx.fcitx5.android.input.ai.prediction.source

import org.fcitx.fcitx5.android.input.ai.AiPrediction
import org.fcitx.fcitx5.android.input.ai.ImmediateContextualPredictions

private const val DISCOURSE_CONTINUATION = "discourse_continuation"

/**
 * [ImmediateContextualPredictions] results other than discourse continuations: sentence-pack
 * continuations and the other immediate candidates, ranked alongside the personal sources.
 */
internal class ImmediateContinuationSource : CandidateSource {
    override fun collect(request: CandidateRequest): List<AiPrediction> =
        request.immediatePredictions.filterNot { it.source == DISCOURSE_CONTINUATION }
}

/**
 * Discourse continuations from [ImmediateContextualPredictions], listed last so every other source
 * wins a duplicate text over them.
 */
internal class DeferredDiscourseSource : CandidateSource {
    override fun collect(request: CandidateRequest): List<AiPrediction> =
        request.immediatePredictions.filter { it.source == DISCOURSE_CONTINUATION }
}
