/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AiProviderProfileTest {
    @Test
    fun `valid profile normalizes endpoint and selects tier models`() {
        val profile = AiProviderProfile(
            baseUrl = " https://example.test/v1/ ",
            apiKey = " secret ",
            fastModel = "fast",
            balancedModel = "balanced",
            qualityModel = "quality"
        ).validate()

        assertEquals("https://example.test/v1", profile.baseUrl)
        assertEquals("https://example.test/v1/responses", profile.responsesEndpoint)
        assertEquals("fast", profile.model(AiModelTier.Fast))
        assertEquals("balanced", profile.model(AiModelTier.Balanced))
        assertEquals("quality", profile.model(AiModelTier.Quality))
    }

    @Test
    fun `remote plaintext and credential-bearing urls are rejected`() {
        assertFalse(AiProviderProfile(baseUrl = "http://example.test/v1", apiKey = "x").isConfigured)
        assertFalse(AiProviderProfile(baseUrl = "https://user:pass@example.test/v1", apiKey = "x").isConfigured)
        assertFalse(AiProviderProfile(baseUrl = "https://example.test/v1?q=x", apiKey = "x").isConfigured)
        assertTrue(AiProviderProfile(baseUrl = "http://127.0.0.1:4000/v1", apiKey = "x").isConfigured)
    }
}
