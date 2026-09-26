/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 YunChan
 */
package org.fcitx.fcitx5.android.ads

/**
 * Pure local frequency evaluation for an AVENUE venue.
 * A clock that went backwards keeps the venue blocked instead of
 * resetting its cap (see docs 3.2 invariants).
 */
internal data class AvenueFrequencyState(
    val dayIndex: Long = 0L,
    val shownToday: Int = 0,
    val lastShownEpochMs: Long = 0L,
    val actionsTotal: Int = 0
)

internal object AvenueFrequencyPolicy {
    private const val DAY_MS = 86_400_000L

    fun evaluate(
        venue: AdVenue,
        state: AvenueFrequencyState,
        nowEpochMs: Long
    ): BlockReason? {
        val today = nowEpochMs / DAY_MS
        if (state.dayIndex > today) return BlockReason.COOLDOWN_ACTIVE
        val shownToday = if (state.dayIndex == today) state.shownToday else 0
        if (shownToday >= venue.dailyCap) return BlockReason.FREQUENCY_CAPPED
        val cooldownMs = venue.cooldownMinutes * 60_000L
        if (state.lastShownEpochMs > 0L && nowEpochMs - state.lastShownEpochMs < cooldownMs) {
            return BlockReason.COOLDOWN_ACTIVE
        }
        if (state.actionsTotal < venue.minActions) return BlockReason.MIN_ACTIONS_NOT_MET
        return null
    }
}
