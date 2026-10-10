/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 YunChan
 */
package org.fcitx.fcitx5.android.input.ai.ondevice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AutomaticSuggestionReplacementTest {

    @Test
    fun `range covers the stored sentence at the end of the text before the cursor`() {
        val range = requireNotNull(
            AutomaticSuggestionReplacement.rangeToReplace("회의 시작했어요. 안녕하세여", "안녕하세여")
        )

        assertEquals(10, range.start)
        assertEquals(15, range.end)
    }

    @Test
    fun `range covers the whole text when the sentence is all there is`() {
        val range = requireNotNull(AutomaticSuggestionReplacement.rangeToReplace("안녕하세여", "안녕하세여"))

        assertEquals(0, range.start)
        assertEquals(5, range.end)
    }

    @Test
    fun `typed sentence with trailing space is replaced including that space`() {
        val text = "지난번 애기하신 자료 "
        val range = requireNotNull(AutomaticSuggestionReplacement.rangeToReplace(text, text))

        assertEquals(text.length, range.end - range.start)
    }

    @Test
    fun `candidate is stale when the user typed or deleted after it was stored`() {
        assertNull(AutomaticSuggestionReplacement.rangeToReplace("안녕하세여 안", "안녕하세여"))
        assertNull(AutomaticSuggestionReplacement.rangeToReplace("안녕하세", "안녕하세여"))
        assertNull(AutomaticSuggestionReplacement.rangeToReplace("안녕하세요", "안녕하세여"))
        assertNull(AutomaticSuggestionReplacement.rangeToReplace("안녕하세여", ""))
    }

    @Test
    fun `replacement text must be printable`() {
        assertTrue(AutomaticSuggestionReplacement.isReplacementSafe("안녕하세요."))
        assertFalse(AutomaticSuggestionReplacement.isReplacementSafe("   "))
        assertFalse(AutomaticSuggestionReplacement.isReplacementSafe("안녕\n하세요."))
        assertFalse(AutomaticSuggestionReplacement.isReplacementSafe("안녕​하세요."))
    }
}
