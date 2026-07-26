/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AiProviderDiscoveryManifestTest {
    @Test
    fun `trusted manifest becomes an OAuth profile without credentials`() {
        val verified = AiProviderDiscoveryManifestCodec.decode(
            validManifest(),
            MANIFEST_URL
        )

        assertEquals("home-ai", verified.providerId)
        assertEquals(AiAuthMode.OAuthPkce, verified.profile.authMode)
        assertEquals("", verified.profile.apiKey)
        assertEquals("Home AI", verified.profile.displayName)
        assertEquals("https://computer.example/v1", verified.profile.baseUrl)
        assertEquals("fast", verified.profile.fastModel)
        assertTrue("responses" in verified.capabilities)
    }

    @Test
    fun `manifest rejects embedded secrets and tokens`() {
        val withSecret = validManifest().replace(
            "\"client_id\": \"fcitx-android-public\"",
            "\"client_id\": \"fcitx-android-public\", \"client_secret\": \"secret\""
        )
        val withToken = validManifest().replace(
            "\"capabilities\"",
            "\"access_token\": \"token\", \"capabilities\""
        )

        assertTrue(runCatching {
            AiProviderDiscoveryManifestCodec.decode(withSecret, MANIFEST_URL)
        }.isFailure)
        assertTrue(runCatching {
            AiProviderDiscoveryManifestCodec.decode(withToken, MANIFEST_URL)
        }.isFailure)
    }

    @Test
    fun `manifest rejects insecure source and mismatched redirect`() {
        assertTrue(runCatching {
            AiProviderDiscoveryManifestCodec.decode(
                validManifest(),
                "http://computer.example${AiProviderDiscoveryManifestCodec.WELL_KNOWN_PATH}"
            )
        }.isFailure)
        assertTrue(runCatching {
            AiProviderDiscoveryManifestCodec.decode(
                validManifest().replace(
                    AiProviderProfile.oauthRedirectUri,
                    "org.example.other:/callback"
                ),
                MANIFEST_URL
            )
        }.isFailure)
    }

    @Test
    fun `manifest requires Responses capability and fixed well-known path`() {
        assertTrue(runCatching {
            AiProviderDiscoveryManifestCodec.decode(
                validManifest().replace("\"responses\", ", ""),
                MANIFEST_URL
            )
        }.isFailure)
        assertTrue(runCatching {
            AiProviderDiscoveryManifestCodec.decode(
                validManifest(),
                "https://computer.example/provider.json"
            )
        }.isFailure)
        assertTrue(runCatching {
            AiProviderDiscoveryManifestCodec.decode(
                validManifest().replace("\"fast\": \"fast\"", "\"fast\": \"\""),
                MANIFEST_URL
            )
        }.isFailure)
    }

    @Test
    fun `manual address adds well-known path and rejects unsafe variants`() {
        assertEquals(
            MANIFEST_URL,
            AiProviderDiscoveryManager.normalizeManifestUrl("https://computer.example")
        )
        assertEquals(
            MANIFEST_URL,
            AiProviderDiscoveryManager.normalizeManifestUrl(MANIFEST_URL)
        )
        assertEquals(null, AiProviderDiscoveryManager.normalizeManifestUrl("http://computer.example"))
        assertEquals(null, AiProviderDiscoveryManager.normalizeManifestUrl("https://user@computer.example"))
        assertFalse(AiProviderDiscoveryManager.isAllowedManifestUrl("https://computer.example/other"))
    }

    private fun validManifest(): String = """
        {
          "protocol_version": 1,
          "provider_id": "home-ai",
          "display_name": "Home AI",
          "base_url": "https://computer.example/v1",
          "oauth": {
            "authorization_endpoint": "https://computer.example/oauth/authorize",
            "token_endpoint": "https://computer.example/oauth/token",
            "revocation_endpoint": "https://computer.example/oauth/revoke",
            "client_id": "fcitx-android-public",
            "scopes": ["openid", "offline_access", "ai.invoke"],
            "redirect_uri": "${AiProviderProfile.oauthRedirectUri}"
          },
          "models": {
            "fast": "fast",
            "balanced": "balanced",
            "quality": "quality"
          },
          "capabilities": ["responses", "transcription"]
        }
    """.trimIndent()

    private companion object {
        const val MANIFEST_URL =
            "https://computer.example${AiProviderDiscoveryManifestCodec.WELL_KNOWN_PATH}"
    }
}
