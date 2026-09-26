/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 YunChan
 */
package org.fcitx.fcitx5.android.input.ai.ondevice

/**
 * Pure sliding-window budget for automatic-suggestion engine recovery attempts. At most
 * [maxRecoveries] recoveries may be consumed within any trailing [windowMs]-wide window; once the
 * window's slots are exhausted, further attempts are refused until enough time passes for an
 * earlier consumption to fall outside the window.
 */
class OnDeviceRecoveryBudget(
    private val windowMs: Long = 600_000L,
    private val maxRecoveries: Int = 3
) {

    private val consumptions = ArrayDeque<Long>()

    /** Attempts to consume one recovery at [nowMs]. Returns whether it was granted. */
    @Synchronized
    fun tryConsume(nowMs: Long): Boolean {
        prune(nowMs)
        if (consumptions.size >= maxRecoveries) return false
        consumptions.addLast(nowMs)
        return true
    }

    /** Clears all recorded consumptions, restoring the budget to fully available. */
    @Synchronized
    fun reset() {
        consumptions.clear()
    }

    /** The number of consumptions still counted within the trailing window ending at [nowMs]. */
    @Synchronized
    fun consumedInWindow(nowMs: Long): Int {
        prune(nowMs)
        return consumptions.size
    }

    private fun prune(nowMs: Long) {
        consumptions.removeAll { nowMs - it >= windowMs }
    }
}
