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
        GraphEnrichmentFailure.REAUTH_REQUIRED -> R.string.graph_enrichment_failure_reauth_required
        GraphEnrichmentFailure.PROVIDER_BUSY -> R.string.graph_enrichment_failure_provider_busy
        GraphEnrichmentFailure.TIMEOUT -> R.string.graph_enrichment_failure_timeout
        GraphEnrichmentFailure.NETWORK -> R.string.graph_enrichment_failure_network
        GraphEnrichmentFailure.PROVIDER_ERROR -> R.string.graph_enrichment_failure_provider_error
        GraphEnrichmentFailure.STORAGE -> R.string.graph_enrichment_failure_storage
        GraphEnrichmentFailure.MODEL_UNAVAILABLE -> R.string.graph_enrichment_failure_model_unavailable
        GraphEnrichmentFailure.DEVICE_BUSY -> R.string.graph_enrichment_failure_device_busy
        GraphEnrichmentFailure.NONE,
        GraphEnrichmentFailure.UNKNOWN -> R.string.enrichment_status_failed
    }

    fun of(failure: GraphEnrichmentFailure, ctx: Context): String = ctx.getString(resourceIdFor(failure))

    fun requiresReauth(failure: GraphEnrichmentFailure): Boolean =
        failure == GraphEnrichmentFailure.REAUTH_REQUIRED

    /**
     * True for the failure kinds the old external AI-provider enrichment path used to record
     * (network, OAuth, provider HTTP errors). These can still be decoded from prefs written before
     * the on-device Gemma migration; the dashboard treats them as if enrichment had never run
     * rather than showing a stale provider-connection error the user can no longer act on.
     */
    fun isLegacyProviderFailure(failure: GraphEnrichmentFailure): Boolean = failure in LEGACY_PROVIDER_FAILURES

    private val LEGACY_PROVIDER_FAILURES = setOf(
        GraphEnrichmentFailure.PROVIDER_ERROR,
        GraphEnrichmentFailure.PROVIDER_BUSY,
        GraphEnrichmentFailure.NETWORK,
        GraphEnrichmentFailure.REAUTH_REQUIRED,
        GraphEnrichmentFailure.TIMEOUT
    )
}
