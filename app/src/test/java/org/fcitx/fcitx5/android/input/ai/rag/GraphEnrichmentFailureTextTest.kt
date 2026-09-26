/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 YunChan
 */
package org.fcitx.fcitx5.android.input.ai.rag

import org.fcitx.fcitx5.android.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Exercises [GraphEnrichmentFailureText.resourceIdFor] directly (no Android
 * [android.content.res.Resources] instance is available in a plain JVM unit test) to fix the
 * failure-to-string mapping, and [GraphEnrichmentFailureText.requiresReauth].
 */
class GraphEnrichmentFailureTextTest {

    @Test
    fun everyFailureMapsToTheDashboardsFailureString() {
        assertEquals(
            R.string.graph_enrichment_failure_invalid_response,
            GraphEnrichmentFailureText.resourceIdFor(GraphEnrichmentFailure.INVALID_RESPONSE)
        )
        assertEquals(
            R.string.graph_enrichment_failure_reauth_required,
            GraphEnrichmentFailureText.resourceIdFor(GraphEnrichmentFailure.REAUTH_REQUIRED)
        )
        assertEquals(
            R.string.graph_enrichment_failure_provider_busy,
            GraphEnrichmentFailureText.resourceIdFor(GraphEnrichmentFailure.PROVIDER_BUSY)
        )
        assertEquals(
            R.string.graph_enrichment_failure_timeout,
            GraphEnrichmentFailureText.resourceIdFor(GraphEnrichmentFailure.TIMEOUT)
        )
        assertEquals(
            R.string.graph_enrichment_failure_network,
            GraphEnrichmentFailureText.resourceIdFor(GraphEnrichmentFailure.NETWORK)
        )
        assertEquals(
            R.string.graph_enrichment_failure_provider_error,
            GraphEnrichmentFailureText.resourceIdFor(GraphEnrichmentFailure.PROVIDER_ERROR)
        )
        assertEquals(
            R.string.graph_enrichment_failure_storage,
            GraphEnrichmentFailureText.resourceIdFor(GraphEnrichmentFailure.STORAGE)
        )
        assertEquals(
            R.string.graph_enrichment_failure_model_unavailable,
            GraphEnrichmentFailureText.resourceIdFor(GraphEnrichmentFailure.MODEL_UNAVAILABLE)
        )
        assertEquals(
            R.string.graph_enrichment_failure_device_busy,
            GraphEnrichmentFailureText.resourceIdFor(GraphEnrichmentFailure.DEVICE_BUSY)
        )
    }

    @Test
    fun noneAndUnknownFallBackToTheGenericFailedMessage() {
        assertEquals(
            R.string.enrichment_status_failed,
            GraphEnrichmentFailureText.resourceIdFor(GraphEnrichmentFailure.NONE)
        )
        assertEquals(
            R.string.enrichment_status_failed,
            GraphEnrichmentFailureText.resourceIdFor(GraphEnrichmentFailure.UNKNOWN)
        )
    }

    @Test
    fun legacyProviderFailuresAreExactlyTheOldExternalProviderKinds() {
        val legacy = setOf(
            GraphEnrichmentFailure.PROVIDER_ERROR,
            GraphEnrichmentFailure.PROVIDER_BUSY,
            GraphEnrichmentFailure.NETWORK,
            GraphEnrichmentFailure.REAUTH_REQUIRED,
            GraphEnrichmentFailure.TIMEOUT
        )
        GraphEnrichmentFailure.entries.forEach { failure ->
            assertEquals(
                "$failure legacy-provider classification",
                failure in legacy,
                GraphEnrichmentFailureText.isLegacyProviderFailure(failure)
            )
        }
    }

    @Test
    fun onlyReauthRequiredNeedsTheReauthAction() {
        assertTrue(GraphEnrichmentFailureText.requiresReauth(GraphEnrichmentFailure.REAUTH_REQUIRED))
        GraphEnrichmentFailure.entries
            .filter { it != GraphEnrichmentFailure.REAUTH_REQUIRED }
            .forEach { failure ->
                assertFalse(
                    "$failure should not require reauth",
                    GraphEnrichmentFailureText.requiresReauth(failure)
                )
            }
    }
}
