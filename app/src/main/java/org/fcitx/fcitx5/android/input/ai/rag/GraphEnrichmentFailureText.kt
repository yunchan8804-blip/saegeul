/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 YunChan
 */
package org.fcitx.fcitx5.android.input.ai.rag

import android.content.Context
import androidx.annotation.StringRes
import org.fcitx.fcitx5.android.R

/**
 * Maps a [GraphEnrichmentFailure] to the user-facing reason text, reusing the same
 * `graph_enrichment_failure_*` strings the dashboard already shows for the same failures.
 */
object GraphEnrichmentFailureText {

    @StringRes
    fun resourceIdFor(failure: GraphEnrichmentFailure): Int = when (failure) {
        GraphEnrichmentFailure.INVALID_RESPONSE -> R.string.graph_enrichment_failure_invalid_response
        GraphEnrichmentFailure.STORAGE -> R.string.graph_enrichment_failure_storage
        GraphEnrichmentFailure.MODEL_UNAVAILABLE -> R.string.graph_enrichment_failure_model_unavailable
        GraphEnrichmentFailure.DEVICE_BUSY -> R.string.graph_enrichment_failure_device_busy
        GraphEnrichmentFailure.NONE,
        GraphEnrichmentFailure.UNKNOWN -> R.string.enrichment_status_failed
    }

    fun of(failure: GraphEnrichmentFailure, ctx: Context): String = ctx.getString(resourceIdFor(failure))
}
