/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.buffered

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BufferedDeleteSurroundingTest {

    @Test
    fun deleteLastCodePointsRemovesWholeBuffer() {
        val controller = BufferedInputController()
        controller.capture("한국")

        assertEquals(2, controller.deleteLastCodePoints(2))
        assertEquals("", controller.prefix)
        assertTrue(controller.isEmpty)
        assertEquals(BufferedSessionState.Idle, controller.state)
    }

    @Test
    fun deleteLastCodePointsReportsActualCountWhenBufferIsShorter() {
        val controller = BufferedInputController()
        controller.capture("한국")

        assertEquals(2, controller.deleteLastCodePoints(3))
        assertTrue(controller.isEmpty)
    }

    @Test
    fun deleteLastCodePointsKeepsEarlierText() {
        val controller = BufferedInputController()
        controller.capture("한국어")

        assertEquals(2, controller.deleteLastCodePoints(2))
        assertEquals("한", controller.prefix)
        assertEquals(BufferedSessionState.Composing("한"), controller.state)
    }

    @Test
    fun deleteLastCodePointsTreatsSurrogatePairAsOne() {
        val controller = BufferedInputController()
        controller.capture("한😀")

        assertEquals(1, controller.deleteLastCodePoints(1))
        assertEquals("한", controller.prefix)
    }

    @Test
    fun deleteLastCodePointsCountsEachCodePointOfCombiningSequence() {
        val controller = BufferedInputController()
        controller.capture("가é")

        assertEquals(1, controller.deleteLastCodePoints(1))
        assertEquals("가e", controller.prefix)
    }

    @Test
    fun deleteLastCodePointsOnEmptyBufferOrNonPositiveCountDeletesNothing() {
        val controller = BufferedInputController()
        assertEquals(0, controller.deleteLastCodePoints(2))

        controller.capture("한")
        assertEquals(0, controller.deleteLastCodePoints(0))
        assertEquals(0, controller.deleteLastCodePoints(-1))
        assertEquals("한", controller.prefix)
    }

    @Test
    fun planDeletesExactBufferLength() {
        val plan = BufferedDeleteSurrounding.plan(
            bufferCodePoints = 2, before = 2, after = 0, resetPending = false
        )

        assertEquals(BufferedDeleteSurrounding.Plan(deleteFromBuffer = 2, dropped = 0), plan)
    }

    @Test
    fun planDropsOverflowBeyondBuffer() {
        val plan = BufferedDeleteSurrounding.plan(
            bufferCodePoints = 1, before = 2, after = 0, resetPending = false
        )

        assertEquals(BufferedDeleteSurrounding.Plan(deleteFromBuffer = 1, dropped = 1), plan)
    }

    @Test
    fun planDropsEverythingWhileEngineResetIsPending() {
        val plan = BufferedDeleteSurrounding.plan(
            bufferCodePoints = 5, before = 2, after = 1, resetPending = true
        )

        assertEquals(BufferedDeleteSurrounding.Plan(deleteFromBuffer = 0, dropped = 3), plan)
    }

    @Test
    fun planDropsAfterCursorDeletion() {
        val plan = BufferedDeleteSurrounding.plan(
            bufferCodePoints = 3, before = 1, after = 2, resetPending = false
        )

        assertEquals(BufferedDeleteSurrounding.Plan(deleteFromBuffer = 1, dropped = 2), plan)
    }

    @Test
    fun planWithEmptyBufferDropsEverything() {
        val plan = BufferedDeleteSurrounding.plan(
            bufferCodePoints = 0, before = 2, after = 0, resetPending = false
        )

        assertEquals(BufferedDeleteSurrounding.Plan(deleteFromBuffer = 0, dropped = 2), plan)
    }

    @Test
    fun hanjaReplacementSequenceLeavesOnlyTheConvertedText() {
        val controller = BufferedInputController()
        controller.capture("한")
        controller.capture("국")

        val plan = BufferedDeleteSurrounding.plan(
            bufferCodePoints = controller.prefix.codePointCount(0, controller.prefix.length),
            before = 2,
            after = 0,
            resetPending = false
        )
        controller.deleteLastCodePoints(plan.deleteFromBuffer)
        controller.capture("韓國")

        assertEquals(0, plan.dropped)
        assertEquals("韓國", controller.snapshot())
    }
}
