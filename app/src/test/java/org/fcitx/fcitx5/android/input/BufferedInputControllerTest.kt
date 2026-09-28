/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BufferedInputControllerTest {

    @Test
    fun combinesFinalizedPrefixAndCurrentPreedit() {
        val controller = BufferedInputController()
        controller.capture("한")

        assertEquals("한글", controller.snapshot("글"))
        assertEquals("한", controller.prefix)
    }

    @Test
    fun deletesOneSurrogatePairAsOneCharacter() {
        val controller = BufferedInputController()
        controller.capture("한😀")

        assertTrue(controller.deleteLastCodePoint())
        assertEquals("한", controller.prefix)
        assertTrue(controller.deleteLastCodePoint())
        assertTrue(controller.isEmpty)
        assertFalse(controller.deleteLastCodePoint())
    }

    @Test
    fun deletesZwjEmojiSequenceAsOneCharacter() {
        val family = "👨‍👩‍👧"
        val controller = BufferedInputController()
        controller.capture("가$family")

        assertTrue(controller.deleteLastCodePoint())
        assertEquals("가", controller.prefix)
    }

    @Test
    fun deletesOneFlagAtATime() {
        val korea = "🇰🇷"
        val japan = "🇯🇵"
        val controller = BufferedInputController()
        controller.capture("가$korea$japan")

        assertTrue(controller.deleteLastCodePoint())
        assertEquals("가$korea", controller.prefix)
        assertTrue(controller.deleteLastCodePoint())
        assertEquals("가", controller.prefix)
    }

    @Test
    fun deletesLetterWithCombiningMarkAsOneCharacter() {
        val controller = BufferedInputController()
        controller.capture("가é")

        assertTrue(controller.deleteLastCodePoint())
        assertEquals("가", controller.prefix)
    }

    @Test
    fun deletesPrecomposedAndConjoiningHangulSyllablesWhole() {
        val conjoiningHan = "한"
        val controller = BufferedInputController()
        controller.capture("새글$conjoiningHan")

        assertTrue(controller.deleteLastCodePoint())
        assertEquals("새글", controller.prefix)
        assertTrue(controller.deleteLastCodePoint())
        assertEquals("새", controller.prefix)
        assertTrue(controller.deleteLastCodePoint())
        assertTrue(controller.isEmpty)
    }

    @Test
    fun surrogateHalvesCapturedSeparatelyAreDeletedTogether() {
        val controller = BufferedInputController()
        controller.capture("가\uD83D")
        controller.capture("\uDE00")

        assertEquals("가😀", controller.prefix)
        assertTrue(controller.deleteLastCodePoint())
        assertEquals("가", controller.prefix)
    }

    @Test
    fun clearStartsANewSession() {
        val controller = BufferedInputController()
        controller.capture("이전 입력")

        controller.clear()

        assertEquals("", controller.snapshot())
    }
}
