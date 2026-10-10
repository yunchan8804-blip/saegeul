/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input

import org.fcitx.fcitx5.android.input.buffered.BufferedInputController
import org.fcitx.fcitx5.android.input.buffered.BufferedInputTransport
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
    fun everyEditOfTheBufferedTextChangesTheRevision() {
        val controller = BufferedInputController()
        val edits = listOf<(BufferedInputController) -> Unit>(
            { it.capture("한글") },
            { it.deleteLastCharacter() },
            { it.capture("입력") },
            { it.deleteLastCodePoints(1) },
            { it.markSubmitted(BufferedInputTransport.DirectCommit) },
            { it.capture("다시") },
            { it.markDeliveryFailed(BufferedInputTransport.DirectCommit) },
            { it.cancel() },
            { it.capture("끝") },
            { it.clear() }
        )

        edits.forEachIndexed { index, edit ->
            val before = controller.revision
            edit(controller)
            assertTrue("edit $index", controller.revision > before)
        }
    }

    @Test
    fun readingTheBufferedTextKeepsTheRevision() {
        val controller = BufferedInputController()
        controller.capture("한")
        val before = controller.revision

        controller.snapshot("글")
        controller.prefix
        controller.extractForCopy()
        controller.prepareRetry()

        assertEquals(before, controller.revision)
    }

    @Test
    fun clearStartsANewSession() {
        val controller = BufferedInputController()
        controller.capture("이전 입력")

        controller.clear()

        assertEquals("", controller.snapshot())
    }
}
