/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.buffered

/**
 * Decides how an engine DeleteSurrounding request is applied while Hangul text is held in the
 * compatibility buffer. The engine counts in code points and expects the text it committed to be
 * removable, but that text lives in the buffer, never in the editor.
 */
object BufferedDeleteSurrounding {

    /** [deleteFromBuffer] code points leave the buffer end; [dropped] requested ones are ignored. */
    data class Plan(val deleteFromBuffer: Int, val dropped: Int)

    fun plan(bufferCodePoints: Int, before: Int, after: Int, resetPending: Boolean): Plan {
        val requestedBefore = before.coerceAtLeast(0)
        val requestedAfter = after.coerceAtLeast(0)
        if (resetPending) {
            return Plan(deleteFromBuffer = 0, dropped = requestedBefore + requestedAfter)
        }
        val deletable = minOf(requestedBefore, bufferCodePoints.coerceAtLeast(0))
        return Plan(
            deleteFromBuffer = deletable,
            dropped = requestedBefore - deletable + requestedAfter
        )
    }
}
