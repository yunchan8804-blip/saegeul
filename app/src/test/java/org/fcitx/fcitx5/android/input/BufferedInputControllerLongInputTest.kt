/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input

import org.fcitx.fcitx5.android.input.buffered.BufferedInputController
import org.fcitx.fcitx5.android.input.buffered.BufferedInputTransport
import org.fcitx.fcitx5.android.input.buffered.BufferTerminationEvent
import org.fcitx.fcitx5.android.input.buffered.BufferTerminationResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BufferedInputControllerLongInputTest {

    private val characters = List(CHARACTER_COUNT) { index -> MIXED_CHARACTERS[index % MIXED_CHARACTERS.size] }
    private val text = characters.joinToString("")

    @Test
    fun capturesAndExtractsLongInputUnchanged() {
        val controller = BufferedInputController()
        characters.forEach(controller::capture)

        assertEquals(text, controller.prefix)
        assertEquals(text + "한", controller.snapshot("한"))
        assertEquals(text + "한", controller.extractForCopy("한"))
        assertEquals(text, controller.prefix)
    }

    @Test
    fun deletesLongInputOneCharacterAtATimeWithoutSplittingSurrogates() {
        val controller = BufferedInputController()
        controller.capture(text)
        var expectedLength = text.length

        for (index in characters.indices.reversed()) {
            assertTrue(controller.deleteLastCharacter())
            expectedLength -= characters[index].length
            val prefix = controller.prefix
            assertEquals(text.substring(0, expectedLength), prefix)
            assertFalse(prefix.isNotEmpty() && Character.isHighSurrogate(prefix.last()))
        }
        assertTrue(controller.isEmpty)
        assertFalse(controller.deleteLastCharacter())
    }

    @Test
    fun submitsLongInputAsOneUnitAndEmptiesBuffer() {
        val controller = BufferedInputController()
        val events = mutableListOf<BufferTerminationEvent>()
        controller.setTerminationListener { events += it }
        characters.forEach(controller::capture)

        val submitted = controller.markSubmitted(BufferedInputTransport.DirectCommit, "한")

        assertEquals(text + "한", submitted)
        assertTrue(controller.isEmpty)
        assertEquals(BufferTerminationResult.Submitted, events.single().result)
        assertEquals(submitted.length, events.single().characterCount)
    }

    @Test
    fun failedLongDeliveryKeepsEveryCharacterForRetry() {
        val controller = BufferedInputController()
        characters.forEach(controller::capture)

        controller.markDeliveryFailed(BufferedInputTransport.SystemPaste, "한")

        assertEquals(text + "한", controller.prepareRetry())
        assertEquals(text + "한", controller.prefix)
    }

    private companion object {
        const val CHARACTER_COUNT = 10_000

        /** Hangul, ASCII, surrogate pairs, a combining mark and conjoining jamo, one character each. */
        val MIXED_CHARACTERS = listOf(
            "가",
            "😀",
            "a",
            "e\u0301",
            "\uD840\uDC00",
            "\u1112\u1161\u11AB"
        )
    }
}
