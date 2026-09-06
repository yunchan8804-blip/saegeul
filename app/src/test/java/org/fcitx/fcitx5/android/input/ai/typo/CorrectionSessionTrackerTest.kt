/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 YunChan
 */
package org.fcitx.fcitx5.android.input.ai.typo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for CorrectionSessionTracker: 백스페이스로 시작된 "지우고 다시 쓴" 세션이
 * 어절 경계에서 올바른 쌍으로 확정되는지, 타임아웃·편집기 전환에서 취소되는지 검증한다.
 */
class CorrectionSessionTrackerTest {

    @Test
    fun `backspace then word boundary returns the abandoned and final word pair`() {
        val tracker = CorrectionSessionTracker()
        tracker.onBackspace("사묘ㅏ함니다")

        val pair = tracker.onWordBoundary("감사합니다")

        assertEquals("사묘ㅏ함니다" to "감사합니다", pair)
    }

    @Test
    fun `word boundary returns null when abandoned equals final word`() {
        val tracker = CorrectionSessionTracker()
        tracker.onBackspace("감사합니다")

        val pair = tracker.onWordBoundary("감사합니다")

        assertNull(pair)
    }

    @Test
    fun `word boundary without an active session returns null`() {
        val tracker = CorrectionSessionTracker()

        val pair = tracker.onWordBoundary("감사합니다")

        assertNull(pair)
    }

    @Test
    fun `session auto-cancels after the timeout elapses`() {
        val clockValue = longArrayOf(0L)
        val tracker = CorrectionSessionTracker(clock = { clockValue[0] }, timeoutMs = 15_000)
        tracker.onBackspace("사묘ㅏ함니다")
        assertTrue(tracker.isActive())

        clockValue[0] = 15_001L

        assertFalse(tracker.isActive())
        assertNull(tracker.onWordBoundary("감사합니다"))
    }

    @Test
    fun `onEditorChanged cancels an active session`() {
        val tracker = CorrectionSessionTracker()
        tracker.onBackspace("사묘ㅏ함니다")
        assertTrue(tracker.isActive())

        tracker.onEditorChanged()

        assertFalse(tracker.isActive())
        assertNull(tracker.onWordBoundary("감사합니다"))
    }

    @Test
    fun `second backspace during an active session keeps the first snapshot`() {
        val tracker = CorrectionSessionTracker()
        tracker.onBackspace("사묘ㅏ함니다")
        tracker.onBackspace("사묘ㅏ함니")

        val pair = tracker.onWordBoundary("감사합니다")

        assertEquals("사묘ㅏ함니다" to "감사합니다", pair)
    }
}
