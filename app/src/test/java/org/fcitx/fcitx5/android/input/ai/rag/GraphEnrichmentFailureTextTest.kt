/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 YunChan
 */
package org.fcitx.fcitx5.android.input.ai.rag

import org.fcitx.fcitx5.android.R
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Exercises [GraphEnrichmentFailureText.resourceIdFor] directly (no Android
 * [android.content.res.Resources] instance is available in a plain JVM unit test) to fix the
 * failure-to-string mapping.
 */
class GraphEnrichmentFailureTextTest {

    @Test
    fun everyFailureMapsToTheDashboardsFailureString() {
        assertEquals(
            R.string.graph_enrichment_failure_invalid_response,
            GraphEnrichmentFailureText.resourceIdFor(GraphEnrichmentFailure.INVALID_RESPONSE)
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
}
