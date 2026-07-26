/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.gif

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GifSearchQueryStateTest {
    @Test
    fun composesKoreanQueryWithDubeolsikKeys() {
        val state = GifSearchQueryState()
        "cnrgk".forEach(state::type)

        assertEquals("축하", state.text)
        assertEquals("축하", state.submit())
    }

    @Test
    fun backspaceRecomposesPendingKoreanAndRemovesWholeCodePoint() {
        val state = GifSearchQueryState()
        "gksrmf".forEach(state::type)
        state.backspace()
        assertEquals("한그", state.text)

        val emojiState = GifSearchQueryState("축하🥳")
        emojiState.backspace()
        assertEquals("축하", emojiState.text)
    }

    @Test
    fun languageSwitchCommitsKoreanAndKeepsSearchLocal() {
        val state = GifSearchQueryState()
        "cnrgk".forEach(state::type)
        state.toggleLanguage()
        "gif".forEach(state::type)
        state.space()
        state.toggleShift()
        state.type('x')

        assertEquals(GifQueryLanguage.English, state.language)
        assertFalse(state.shifted)
        assertEquals("축하gif X", state.submit())
    }

    @Test
    fun clearResetsTextAndShift() {
        val state = GifSearchQueryState("기존")
        state.toggleShift()
        state.clear()

        assertTrue(state.text.isEmpty())
        assertFalse(state.shifted)
    }
}
