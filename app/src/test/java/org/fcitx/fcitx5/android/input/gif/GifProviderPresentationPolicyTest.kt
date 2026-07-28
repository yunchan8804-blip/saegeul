/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.gif

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GifProviderPresentationPolicyTest {
    @Test
    fun notoFallbackOnlyOffersReactionQueriesAndExplainsHowToGetMore() {
        val presentation = GifProviderPresentationPolicy.forProvider(
            GifProviderKind.AnimatedNoto
        )

        assertTrue(presentation.showsMoreGifSettings)
        assertEquals(GifQuickSuggestion.Trending, presentation.quickSuggestions.first())
        assertTrue(GifQuickSuggestion.Laugh in presentation.quickSuggestions)
        assertTrue(GifQuickSuggestion.Celebrate in presentation.quickSuggestions)
        assertTrue(GifQuickSuggestion.Thanks in presentation.quickSuggestions)
        assertFalse(GifQuickSuggestion.Meme in presentation.quickSuggestions)
        assertFalse(GifQuickSuggestion.LeaveWork in presentation.quickSuggestions)
        assertFalse(GifQuickSuggestion.Monday in presentation.quickSuggestions)
    }

    @Test
    fun richAndUnavailableProviderSurfacesKeepTheExistingFullChipSet() {
        listOf(
            GifProviderKind.Klipy,
            GifProviderKind.Giphy,
            GifProviderKind.GiphyUnavailable
        ).forEach { kind ->
            val presentation = GifProviderPresentationPolicy.forProvider(kind)

            assertFalse(presentation.showsMoreGifSettings)
            assertEquals(GifQuickSuggestion.Trending, presentation.quickSuggestions.first())
            assertTrue(GifQuickSuggestion.Meme in presentation.quickSuggestions)
            assertTrue(GifQuickSuggestion.LeaveWork in presentation.quickSuggestions)
            assertTrue(GifQuickSuggestion.Monday in presentation.quickSuggestions)
        }
    }
}
