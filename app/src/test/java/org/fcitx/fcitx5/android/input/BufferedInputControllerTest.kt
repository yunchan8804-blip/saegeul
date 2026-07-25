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
    fun deletesOneUnicodeCodePoint() {
        val controller = BufferedInputController()
        controller.capture("한😀")

        assertTrue(controller.deleteLastCodePoint())
        assertEquals("한", controller.prefix)
        assertTrue(controller.deleteLastCodePoint())
        assertTrue(controller.isEmpty)
        assertFalse(controller.deleteLastCodePoint())
    }

    @Test
    fun clearStartsANewSession() {
        val controller = BufferedInputController()
        controller.capture("이전 입력")

        controller.clear()

        assertEquals("", controller.snapshot())
    }
}
