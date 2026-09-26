/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 YunChan
 */
package org.fcitx.fcitx5.android.ads

import android.content.Context

/**
 * On-device exposure and action counters backing the frequency gate.
 * Kept outside AppPrefs so ad bookkeeping never leaks into user settings.
 */
internal class AvenueFrequencyStore(context: Context) {
    private val prefs =
        context.getSharedPreferences("avenue_frequency", Context.MODE_PRIVATE)

    fun state(venueId: String): AvenueFrequencyState {
        val dayIndex = prefs.getLong(key(venueId, "day"), 0L)
        val shownToday = prefs.getInt(key(venueId, "count"), 0)
        val lastShown = prefs.getLong(key(venueId, "last"), 0L)
        val actions = prefs.getInt(key(venueId, "actions"), 0)
        return AvenueFrequencyState(dayIndex, shownToday, lastShown, actions)
    }

    fun recordAction(venueId: String) {
        prefs.edit()
            .putInt(key(venueId, "actions"), state(venueId).actionsTotal + 1)
            .apply()
    }

    fun recordExposure(venueId: String, nowEpochMs: Long) {
        val current = state(venueId)
        val today = nowEpochMs / 86_400_000L
        val effectiveDay = maxOf(current.dayIndex, today)
        val effectiveLast = maxOf(current.lastShownEpochMs, nowEpochMs)
        val shownToday = when {
            today < current.dayIndex -> current.shownToday
            current.dayIndex == today -> current.shownToday + 1
            else -> 1
        }
        prefs.edit()
            .putLong(key(venueId, "day"), effectiveDay)
            .putInt(key(venueId, "count"), shownToday)
            .putLong(key(venueId, "last"), effectiveLast)
            .apply()
    }

    private fun key(venueId: String, suffix: String) = "${venueId}_$suffix"
}
