/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 YunChan
 */
package org.fcitx.fcitx5.android.input.typo

import org.fcitx.fcitx5.android.input.typo.TouchTestFixtures.touch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TouchTraceRecorderTest {

    private fun TouchTraceRecorder.recordKeys(keys: String) = keys.forEach { onLetterTouch(touch(it)) }

    private fun TouchTraceRecorder.trace(): TouchTrace = requireNotNull(snapshot())

    private fun TouchTrace.keysAlignedTo(keys: String): String? =
        alignedTo(keys)?.map { it.key }?.joinToString("")

    @Test
    fun alignsOnlyWhenTheTailKeysAreExactlyTheWordKeys() {
        val recorder = TouchTraceRecorder { true }
        recorder.recordKeys("ekr")
        val trace = recorder.trace()

        assertEquals("kr", trace.keysAlignedTo("kr"))
        assertEquals("ekr", trace.keysAlignedTo("ekr"))
        assertNull(trace.alignedTo("er"))
        assertNull(trace.alignedTo("ek"))
        assertNull(trace.alignedTo("zekr"))
        assertNull(trace.alignedTo(""))
    }

    @Test
    fun shiftedKeyAlignsOnlyWithItsShiftedKeySequence() {
        val recorder = TouchTraceRecorder { true }
        recorder.onLetterTouch(touch('R'))
        recorder.onLetterTouch(touch('k'))
        val trace = recorder.trace()

        assertEquals("Rk", trace.keysAlignedTo(DubeolsikKeyMap.keySequence("까")))
        assertNull(trace.alignedTo(DubeolsikKeyMap.keySequence("가")))
    }

    @Test
    fun withoutLastDropsTheEndingKeys() {
        val recorder = TouchTraceRecorder { true }
        recorder.recordKeys("sksk")
        val trace = recorder.trace()

        assertEquals("sk", trace.withoutLast(2).keysAlignedTo("sk"))
        assertNull(trace.withoutLast(2).alignedTo("sksk"))
        assertNull(trace.withoutLast(9).alignedTo("s"))
    }

    @Test
    fun ringBufferKeepsOnlyTheLatestTouches() {
        val recorder = TouchTraceRecorder(capacity = 4) { true }
        recorder.recordKeys("abcdef")
        val trace = recorder.trace()

        assertEquals("cdef", trace.keysAlignedTo("cdef"))
        assertNull(trace.alignedTo("bcdef"))
    }

    @Test
    fun defaultCapacityIsSixtyFourTouches() {
        val recorder = TouchTraceRecorder { true }
        recorder.recordKeys("a".repeat(70))
        val trace = recorder.trace()

        assertEquals(64, trace.alignedTo("a".repeat(64))?.size)
        assertNull(trace.alignedTo("a".repeat(65)))
    }

    @Test
    fun backspaceRemovesTheLatestTouch() {
        val recorder = TouchTraceRecorder { true }
        recorder.recordKeys("ekr")
        recorder.onBackspace()
        recorder.onLetterTouch(touch('s'))

        assertEquals("eks", recorder.trace().keysAlignedTo("eks"))
    }

    @Test
    fun backspaceOnAnEmptyBufferDoesNothing() {
        val recorder = TouchTraceRecorder { true }
        recorder.onBackspace()
        assertNull(recorder.snapshot())
        recorder.recordKeys("e")
        assertEquals("e", recorder.trace().keysAlignedTo("e"))
    }

    @Test
    fun clearEmptiesTheBuffer() {
        val recorder = TouchTraceRecorder { true }
        recorder.recordKeys("ekr")
        recorder.clear()
        assertNull(recorder.snapshot())
    }

    @Test
    fun blockedRecordingDropsEverythingAlreadyHeld() {
        var allowed = true
        val recorder = TouchTraceRecorder { allowed }
        recorder.recordKeys("ek")

        allowed = false
        recorder.recordKeys("r")
        assertNull(recorder.snapshot())

        allowed = true
        recorder.recordKeys("s")
        assertNull(recorder.trace().alignedTo("ks"))
        assertEquals("s", recorder.trace().keysAlignedTo("s"))
    }

    @Test
    fun blockedRecordingHidesTheSnapshotEvenWithoutANewTouch() {
        var allowed = true
        val recorder = TouchTraceRecorder { allowed }
        recorder.recordKeys("ek")

        allowed = false
        assertNull(recorder.snapshot())
    }

    @Test
    fun snapshotIsNotAffectedByLaterTouches() {
        val recorder = TouchTraceRecorder { true }
        recorder.recordKeys("ek")
        val earlier = recorder.trace()
        recorder.recordKeys("r")

        assertEquals("ek", earlier.keysAlignedTo("ek"))
        assertNull(earlier.alignedTo("kr"))
    }
}
