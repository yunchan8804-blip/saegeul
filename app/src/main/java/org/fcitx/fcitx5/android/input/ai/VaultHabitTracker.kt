/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 YunChan
 */
package org.fcitx.fcitx5.android.input.ai

import android.content.Context

/**
 * Daily habit state for the language vault: streak, streak freezes,
 * and today/yesterday analyzed sentence counts.
 * Pure logic; persistence lives in [VaultHabitStore].
 * See docs/ai-vault-engagement-design.md section 3.
 */
data class VaultHabitState(
    val streak: Int = 0,
    val freezes: Int = VaultHabitTracker.MAX_FREEZES,
    val lastActiveDayIndex: Long = 0L,
    val todayDayIndex: Long = 0L,
    val todaySentences: Int = 0,
    val yesterdaySentences: Int = 0
)

object VaultHabitTracker {
    const val MAX_FREEZES = 2
    private const val DAY_MS = 86_400_000L

    fun onSentencesAnalyzed(
        state: VaultHabitState,
        nowEpochMs: Long,
        analyzed: Int
    ): VaultHabitState {
        if (analyzed <= 0) return state
        val day = nowEpochMs / DAY_MS
        if (day < state.lastActiveDayIndex) return state
        val rolled = rollOver(state, day)
        val addedSentences = (rolled.todaySentences.toLong() + analyzed)
            .coerceAtMost(Int.MAX_VALUE.toLong())
            .toInt()
        return if (rolled.lastActiveDayIndex == day) {
            rolled.copy(todaySentences = addedSentences)
        } else {
            val missedDays = if (rolled.lastActiveDayIndex in 1 until day) {
                day - rolled.lastActiveDayIndex - 1
            } else {
                0L
            }
            var streak = rolled.streak
            var freezes = rolled.freezes
            if (missedDays > 0L) {
                if (missedDays <= freezes) {
                    freezes -= missedDays.toInt()
                } else {
                    freezes = 0
                    streak = 0
                }
            }
            rolled.copy(
                streak = streak + 1,
                freezes = freezes,
                lastActiveDayIndex = day,
                todaySentences = addedSentences
            )
        }
    }

    /**
     * Moves today's counters to yesterday when the day changes.
     * Yesterday keeps its count only if it was the immediately previous day,
     * so a multi-day gap reads as 0 instead of stale data.
     */
    fun rollOver(state: VaultHabitState, day: Long): VaultHabitState {
        if (day <= state.todayDayIndex) return state
        val yesterday = if (state.todayDayIndex == day - 1) state.todaySentences else 0
        return state.copy(
            yesterdaySentences = yesterday,
            todayDayIndex = day,
            todaySentences = 0
        )
    }

    fun dayIndexOf(epochMs: Long): Long = epochMs / DAY_MS
}

class VaultHabitStore(context: Context) {
    private val prefs = context.applicationContext
        .getSharedPreferences("vault_habit", Context.MODE_PRIVATE)

    fun state(): VaultHabitState = VaultHabitState(
        streak = prefs.getInt(KEY_STREAK, 0),
        freezes = prefs.getInt(KEY_FREEZES, VaultHabitTracker.MAX_FREEZES),
        lastActiveDayIndex = prefs.getLong(KEY_LAST_DAY, 0L),
        todayDayIndex = prefs.getLong(KEY_TODAY, 0L),
        todaySentences = prefs.getInt(KEY_TODAY_SENTENCES, 0),
        yesterdaySentences = prefs.getInt(KEY_YESTERDAY_SENTENCES, 0)
    )

    fun record(nowEpochMs: Long, analyzed: Int) {
        save(VaultHabitTracker.onSentencesAnalyzed(state(), nowEpochMs, analyzed))
    }

    private fun save(state: VaultHabitState) {
        prefs.edit()
            .putInt(KEY_STREAK, state.streak)
            .putInt(KEY_FREEZES, state.freezes)
            .putLong(KEY_LAST_DAY, state.lastActiveDayIndex)
            .putLong(KEY_TODAY, state.todayDayIndex)
            .putInt(KEY_TODAY_SENTENCES, state.todaySentences)
            .putInt(KEY_YESTERDAY_SENTENCES, state.yesterdaySentences)
            .apply()
    }

    private companion object {
        const val KEY_STREAK = "streak"
        const val KEY_FREEZES = "freezes"
        const val KEY_LAST_DAY = "last_active_day"
        const val KEY_TODAY = "today_day"
        const val KEY_TODAY_SENTENCES = "today_sentences"
        const val KEY_YESTERDAY_SENTENCES = "yesterday_sentences"
    }
}
