/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.ai

import org.fcitx.fcitx5.android.BuildConfig
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
        assertEquals(setOf("responses"), profile.capabilities)
    }

    @Test
    fun `writing provider requires Responses capability`() {
        assertFalse(oauthProfile().copy(capabilities = setOf("transcription")).isConfigured)
        assertTrue(oauthProfile().copy(capabilities = setOf("responses")).isConfigured)
    }

    @Test
    fun `plaintext and credential-bearing urls are rejected`() {
        assertFalse(AiProviderProfile(baseUrl = "http://example.test/v1", apiKey = "x").isConfigured)
        assertFalse(AiProviderProfile(baseUrl = "https://user:pass@example.test/v1", apiKey = "x").isConfigured)
        assertFalse(AiProviderProfile(baseUrl = "https://example.test/v1?q=x", apiKey = "x").isConfigured)
        assertFalse(AiProviderProfile(baseUrl = "http://127.0.0.1:4000/v1", apiKey = "x").isConfigured)
        assertFalse(AiProviderProfile(baseUrl = "http://100.64.0.8/v1", apiKey = "x").isConfigured)
        assertFalse(AiProviderProfile(baseUrl = "http://host.tail123.ts.net/v1", apiKey = "x").isConfigured)
    }

    @Test
    fun `oauth public client accepts HTTPS tailscale path without API key`() {
        val profile = oauthProfile().validate()

        assertEquals(AiAuthMode.OAuthPkce, profile.authMode)
        assertEquals("", profile.apiKey)
        assertTrue(profile.baseUrl.endsWith(".ts.net/v1"))
    }

    @Test
    fun `oauth rejects mixed credentials and insecure auth endpoints`() {
        assertFalse(oauthProfile(apiKey = "must-not-mix").isConfigured)
        assertFalse(oauthProfile(authorizationEndpoint = "http://127.0.0.1/authorize").isConfigured)
        assertFalse(oauthProfile(tokenEndpoint = "http://100.64.0.8/token").isConfigured)
        assertFalse(
            oauthProfile(revocationEndpoint = "http://server.tail123.ts.net/revoke").isConfigured
        )
    }

    @Test
    fun `oauth callback must match the current profile exactly`() {
        val profile = oauthProfile()
        val matches = AiOAuthCallbackContract.matchesCurrentProfile(
            profile,
            profile.oauthClientId,
            profile.oauthAuthorizationEndpoint,
            profile.oauthTokenEndpoint,
            AiProviderProfile.oauthRedirectUri
        )

        assertTrue(matches)
        assertFalse(
            AiOAuthCallbackContract.matchesCurrentProfile(
                profile,
                "old-client",
                profile.oauthAuthorizationEndpoint,
                profile.oauthTokenEndpoint,
                AiProviderProfile.oauthRedirectUri
            )
        )
        assertFalse(
            AiOAuthCallbackContract.matchesCurrentProfile(
                profile,
                profile.oauthClientId,
                "https://old.example.test/authorize",
                profile.oauthTokenEndpoint,
                AiProviderProfile.oauthRedirectUri
            )
        )
        assertFalse(
            AiOAuthCallbackContract.matchesCurrentProfile(
                profile,
                profile.oauthClientId,
                profile.oauthAuthorizationEndpoint,
                "https://old.example.test/token",
                AiProviderProfile.oauthRedirectUri
            )
        )
        assertFalse(
            AiOAuthCallbackContract.matchesCurrentProfile(
                profile,
                profile.oauthClientId,
                profile.oauthAuthorizationEndpoint,
                profile.oauthTokenEndpoint,
                "org.example.old:/callback"
            )
        )
        assertEquals(
            "${BuildConfig.APPLICATION_ID}.oauth:/callback",
            AiProviderProfile.oauthRedirectUri
        )
    }

    private fun oauthProfile(
        apiKey: String = "",
        authorizationEndpoint: String = "https://server.tail123.ts.net/oauth/authorize",
        tokenEndpoint: String = "https://server.tail123.ts.net/oauth/token",
        revocationEndpoint: String = "https://server.tail123.ts.net/oauth/revoke"
    ) = AiProviderProfile(
        kind = AiProviderKind.OpenAICompatible,
        displayName = "Home AI",
        baseUrl = "https://server.tail123.ts.net/v1",
        authMode = AiAuthMode.OAuthPkce,
        apiKey = apiKey,
        oauthAuthorizationEndpoint = authorizationEndpoint,
        oauthTokenEndpoint = tokenEndpoint,
        oauthRevocationEndpoint = revocationEndpoint,
        oauthClientId = "saegeul-android-public",
        oauthScopes = "openid offline_access ai.invoke"
    )
}
