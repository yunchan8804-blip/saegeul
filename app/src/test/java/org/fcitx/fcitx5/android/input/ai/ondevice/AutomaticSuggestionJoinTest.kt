/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 YunChan
 */
package org.fcitx.fcitx5.android.input.ai.ondevice

import org.junit.Assert.assertEquals
import org.junit.Test

class AutomaticSuggestionJoinTest {

    @Test
    fun `comma suffix drops the space typed before it`() {
        assertEquals("해 보니, 현재는…", AutomaticSuggestionJoin.joined("해 보니 ", ", 현재는…"))
        assertEquals(1, AutomaticSuggestionJoin.trailingSpacesToRemove("해 보니 ", ", 현재는…"))
    }

    @Test
    fun `every attaching mark drops the space`() {
        for (mark in listOf(",", ".", "?", "!", "…", "~", ")", "]")) {
            assertEquals("확인해 보니${mark} 끝", AutomaticSuggestionJoin.joined("확인해 보니 ", "$mark 끝"))
        }
    }

    @Test
    fun `all trailing spaces are removed`() {
        assertEquals("해 보니, 현재는", AutomaticSuggestionJoin.joined("해 보니  ", ", 현재는"))
        assertEquals(2, AutomaticSuggestionJoin.trailingSpacesToRemove("해 보니  ", ", 현재는"))
    }

    @Test
    fun `text without a trailing space is joined as before`() {
        assertEquals("해 보니, 현재는", AutomaticSuggestionJoin.joined("해 보니", ", 현재는"))
        assertEquals(0, AutomaticSuggestionJoin.trailingSpacesToRemove("해 보니", ", 현재는"))
    }

    @Test
    fun `word suffix keeps the typed space`() {
        assertEquals("해 보니 현재는", AutomaticSuggestionJoin.joined("해 보니 ", "현재는"))
        assertEquals("해 보니  현재는", AutomaticSuggestionJoin.joined("해 보니 ", " 현재는"))
        assertEquals(0, AutomaticSuggestionJoin.trailingSpacesToRemove("해 보니 ", "현재는"))
    }

    @Test
    fun `a newline before the cursor is never removed`() {
        assertEquals("해 보니\n, 현재는", AutomaticSuggestionJoin.joined("해 보니\n", ", 현재는"))
    }

    @Test
    fun `stored comma response from the model joins without a space`() {
        val input = OnDeviceSuggestionPolicy.Input(
            textBeforeCursor = "배송 조회를 해 보니 ",
            packageName = "com.example.app",
            inputType = 0,
            imeAction = 0,
            mode = OnDeviceSuggestionPolicy.Mode.SENTENCE
        )
        val stored = OnDeviceSuggestionPolicy.parseForStorage(input, "배송 조회를 해 보니, 현재는 배송이…", NO_TYPO_EVIDENCE)

        assertEquals(", 현재는 배송이…", stored.suffix)
        assertEquals(
            "배송 조회를 해 보니, 현재는 배송이…",
            AutomaticSuggestionJoin.joined(input.textBeforeCursor, requireNotNull(stored.suffix))
        )
    }
}
