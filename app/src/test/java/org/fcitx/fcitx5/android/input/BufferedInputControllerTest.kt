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

        assertTrue(controller.deleteLastCharacter())
        assertEquals("한", controller.prefix)
        assertTrue(controller.deleteLastCharacter())
        assertTrue(controller.isEmpty)
        assertFalse(controller.deleteLastCharacter())
    }

    @Test
    fun deletesLetterWithCombiningMarkAsOneCharacter() {
        val controller = BufferedInputController()
        controller.capture("가e\u0301")

        assertTrue(controller.deleteLastCharacter())
        assertEquals("가", controller.prefix)
    }

    @Test
    fun deletesPrecomposedAndConjoiningHangulSyllablesWhole() {
        val conjoiningHan = "\u1112\u1161\u11AB"
        val controller = BufferedInputController()
        controller.capture("새글$conjoiningHan")

        assertTrue(controller.deleteLastCharacter())
        assertEquals("새글", controller.prefix)
        assertTrue(controller.deleteLastCharacter())
        assertEquals("새", controller.prefix)
        assertTrue(controller.deleteLastCharacter())
        assertTrue(controller.isEmpty)
    }

    @Test
    fun surrogateHalvesCapturedSeparatelyAreDeletedTogether() {
        val controller = BufferedInputController()
        controller.capture("가\uD83D")
        controller.capture("\uDE00")

        assertEquals("가😀", controller.prefix)
        assertTrue(controller.deleteLastCharacter())
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
