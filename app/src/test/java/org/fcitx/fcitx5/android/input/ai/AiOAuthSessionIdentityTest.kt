/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.ai

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AiOAuthSessionIdentityTest {

    @Test
    fun metadataOnlyChangesPreserveOAuthSessionAndFingerprintMaterial() {
        val previous = oauthProfile()
        val next = previous.copy(
            displayName = "Updated Home AI",
            oauthRevocationEndpoint = "https://server.tail123.ts.net/oauth/new-revoke",
            capabilities = setOf("responses", "chat_completions"),
            fastModel = "new-fast",
            balancedModel = "new-balanced",
            qualityModel = "new-quality"
        )

        assertTrue(AiOAuthSessionIdentity.canPreserveSession(previous, next))
        assertTrue(
            AiOAuthSessionIdentity.identityMaterial(previous)
                .contentEquals(AiOAuthSessionIdentity.identityMaterial(next))
        )
    }

    @Test
    fun authenticationIdentityChangesDoNotPreserveOAuthSession() {
        val previous = oauthProfile()

        listOf(
            previous.copy(baseUrl = "https://other.tail123.ts.net/v1"),
            previous.copy(oauthAuthorizationEndpoint = "https://server.tail123.ts.net/oauth/new-authorize"),
            previous.copy(oauthTokenEndpoint = "https://server.tail123.ts.net/oauth/new-token"),
            previous.copy(oauthClientId = "another-public-client"),
            previous.copy(oauthScopes = "openid ai.invoke"),
            previous.copy(authMode = AiAuthMode.ApiKey, apiKey = "api-key")
        ).forEach { next ->
            assertFalse(AiOAuthSessionIdentity.canPreserveSession(previous, next))
        }
        assertFalse(AiOAuthSessionIdentity.canPreserveSession(null, previous))
    }

    @Test
    fun oauthScopeOrderDoesNotChangeSessionIdentity() {
        val previous = oauthProfile()
        val reordered = previous.copy(oauthScopes = "ai.invoke openid offline_access")

        assertTrue(AiOAuthSessionIdentity.canPreserveSession(previous, reordered))
    }

    @Test
    fun authenticationIdentityChangesFingerprintMaterial() {
        val previous = oauthProfile()
        val changedBaseUrl = previous.copy(baseUrl = "https://other.tail123.ts.net/v1")

        assertFalse(
            AiOAuthSessionIdentity.identityMaterial(previous)
                .contentEquals(AiOAuthSessionIdentity.identityMaterial(changedBaseUrl))
        )
    }

    private fun oauthProfile() = AiProviderProfile(
        kind = AiProviderKind.OpenAICompatible,
        displayName = "Home AI",
        baseUrl = "https://server.tail123.ts.net/v1",
        authMode = AiAuthMode.OAuthPkce,
        oauthAuthorizationEndpoint = "https://server.tail123.ts.net/oauth/authorize",
        oauthTokenEndpoint = "https://server.tail123.ts.net/oauth/token",
        oauthRevocationEndpoint = "https://server.tail123.ts.net/oauth/revoke",
        oauthClientId = "saegeul-android-public",
        oauthScopes = "openid offline_access ai.invoke"
    )
}
