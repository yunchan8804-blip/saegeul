/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AiFeatureEntryGateTest {
    @Test
    fun onlyMissingSetupOffersSettingsAction() {
        val missing = AiFeatureEntryGate.evaluate(
            allowsTextInspection = true,
            allowsAiInput = true,
            hasConfiguredProfile = false
        )

        assertEquals(AiFeatureEntryGate.SetupRequired, missing)
        assertTrue(missing.offersSetupAction)
        assertFalse(AiFeatureEntryGate.PrivateEditor.offersSetupAction)
        assertFalse(AiFeatureEntryGate.NetworkPolicyBlocked.offersSetupAction)
        assertFalse(AiFeatureEntryGate.Ready.offersSetupAction)
    }

    @Test
    fun privacyAndPolicyBlocksTakePriorityOverConfiguration() {
        assertEquals(
            AiFeatureEntryGate.PrivateEditor,
            AiFeatureEntryGate.evaluate(false, false, false)
        )
        assertEquals(
            AiFeatureEntryGate.NetworkPolicyBlocked,
            AiFeatureEntryGate.evaluate(true, false, false)
        )
        assertEquals(
            AiFeatureEntryGate.Ready,
            AiFeatureEntryGate.evaluate(true, true, true)
        )
    }

    @Test
    fun oauthProfileNeedsAnEncryptedSessionBeforeTheWritingEntryIsReady() {
        val oauth = AiProviderProfile(
            kind = AiProviderKind.OpenAICompatible,
            displayName = "Home AI",
            baseUrl = "https://home.example.test/v1",
            authMode = AiAuthMode.OAuthPkce,
            oauthAuthorizationEndpoint = "https://home.example.test/oauth/authorize",
            oauthTokenEndpoint = "https://home.example.test/oauth/token",
            oauthClientId = "saegeul-android-public"
        )

        assertFalse(AiProviderReadinessPolicy.isReady(oauth, hasOAuthSession = false))
        assertTrue(AiProviderReadinessPolicy.isReady(oauth, hasOAuthSession = true))
        assertEquals(
            AiFeatureEntryGate.SetupRequired,
            AiFeatureEntryGate.evaluate(
                allowsTextInspection = true,
                allowsAiInput = true,
                hasConfiguredProfile = AiProviderReadinessPolicy.isReady(
                    oauth,
                    hasOAuthSession = false
                )
            )
        )
    }

    @Test
    fun apiKeyProfileDoesNotRequireAnOAuthSession() {
        val apiKey = AiProviderProfile(apiKey = "test-key")

        assertTrue(AiProviderReadinessPolicy.isReady(apiKey, hasOAuthSession = false))
    }
}
