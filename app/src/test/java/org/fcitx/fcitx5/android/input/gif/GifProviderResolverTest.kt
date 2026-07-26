/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.gif

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GifProviderResolverTest {
    @Test
    fun missingCredentialSelectsOpenNotoFallback() {
        val effective = GifProviderResolver.resolve(null)

        assertEquals(GifProviderKind.AnimatedNoto, effective.kind)
        assertTrue(effective.provider is NotoAnimatedEmojiProvider)
        assertEquals(GifProviderCredentialState.Missing, effective.credentialState)
        assertTrue(effective.productionPartnerApprovalRequired)
    }

    @Test
    fun unreadableCredentialSelectsOpenNotoFallbackAndPreservesStatus() {
        val effective = GifProviderResolver.resolve(
            apiKey = null,
            state = GifProviderCredentialState.Unreadable
        )

        assertEquals(GifProviderKind.AnimatedNoto, effective.kind)
        assertEquals(GifProviderCredentialState.Unreadable, effective.credentialState)
    }

    @Test
    fun configuredCredentialSelectsKlipyWithoutExposingItInStatus() {
        val effective = GifProviderResolver.resolve("fake-resolver-value")

        assertEquals(GifProviderKind.Klipy, effective.kind)
        assertTrue(effective.provider is KlipyGifProvider)
        assertEquals(GifProviderCredentialState.Configured, effective.credentialState)
        assertTrue(effective.productionPartnerApprovalRequired)
        assertTrue(effective.toString().contains("fake-resolver-value").not())
    }

    @Test
    fun selectedGiphyWithoutApprovedKeyDoesNotFallBackOrEnableNetwork() {
        val effective = GifProviderResolver.resolve(
            selection = GifProviderSelection.Giphy,
            klipyApiKey = "configured-klipy-key",
            klipyState = GifProviderCredentialState.Configured,
            giphyConfiguration = GiphyProviderConfiguration("giphy-key", false, false),
            giphyState = GiphyCredentialState.KeyOnly
        )

        assertEquals(GifProviderKind.GiphyUnavailable, effective.kind)
        assertEquals(GifProviderSelection.Giphy, effective.selection)
        assertFalse(effective.networkReady)
        assertFalse(effective.provider is KlipyGifProvider)
    }

    @Test
    fun selectedProductionApprovedGiphyUsesIsolatedProvider() {
        val effective = GifProviderResolver.resolve(
            selection = GifProviderSelection.Giphy,
            klipyApiKey = "configured-klipy-key",
            klipyState = GifProviderCredentialState.Configured,
            giphyConfiguration = GiphyProviderConfiguration("giphy-key", true, false),
            giphyState = GiphyCredentialState.Ready
        )

        assertEquals(GifProviderKind.Giphy, effective.kind)
        assertTrue(effective.provider is GiphyGifProvider)
        assertTrue(effective.networkReady)
        assertFalse(effective.giphyMediaCachingApproved)
        assertFalse(effective.productionPartnerApprovalRequired)
    }

    @Test
    fun standardSelectionPreservesConfiguredGiphyStatusForSettings() {
        val effective = GifProviderResolver.resolve(
            selection = GifProviderSelection.Standard,
            klipyApiKey = null,
            klipyState = GifProviderCredentialState.Missing,
            giphyConfiguration = GiphyProviderConfiguration("giphy-key", true, true),
            giphyState = GiphyCredentialState.Ready
        )

        assertEquals(GifProviderKind.AnimatedNoto, effective.kind)
        assertEquals(GiphyCredentialState.Ready, effective.giphyCredentialState)
        assertTrue(effective.giphyMediaCachingApproved)
    }
}
