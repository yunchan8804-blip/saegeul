/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 YunChan
 */
package org.fcitx.fcitx5.android.input.ai

import org.junit.Assert.assertEquals
import org.junit.Test

class VaultHabitTrackerTest {
    private val day1 = 1_000L * 86_400_000L
    private fun at(day: Long) = day * 86_400_000L + 3_600_000L

    @Test
    fun firstActivityStartsStreak() {
        val state = VaultHabitTracker.onSentencesAnalyzed(
            VaultHabitState(), at(100), 10
        )
        assertEquals(1, state.streak)
        assertEquals(10, state.todaySentences)
        assertEquals(100, state.lastActiveDayIndex)
    }

    @Test
    fun sameDayActivityDoesNotIncreaseStreak() {
        val state = VaultHabitTracker.onSentencesAnalyzed(
            VaultHabitState(), at(100), 5
        )
        val next = VaultHabitTracker.onSentencesAnalyzed(state, at(100), 7)
        assertEquals(1, next.streak)
        assertEquals(12, next.todaySentences)
    }

    @Test
    fun consecutiveDayExtendsStreak() {
        var state = VaultHabitState()
        state = VaultHabitTracker.onSentencesAnalyzed(state, at(100), 5)
        state = VaultHabitTracker.onSentencesAnalyzed(state, at(101), 5)
        state = VaultHabitTracker.onSentencesAnalyzed(state, at(102), 5)
        assertEquals(3, state.streak)
    }

    @Test
    fun oneDayGapConsumesFreezeInsteadOfBreakingStreak() {
        var state = VaultHabitState()
        state = VaultHabitTracker.onSentencesAnalyzed(state, at(100), 5)
        state = VaultHabitTracker.onSentencesAnalyzed(state, at(102), 5)
        assertEquals(2, state.streak)
        assertEquals(VaultHabitTracker.MAX_FREEZES - 1, state.freezes)
    }

    @Test
    fun longGapBreaksStreakWhenFreezesRunOut() {
        var state = VaultHabitState()
        state = VaultHabitTracker.onSentencesAnalyzed(state, at(100), 5)
        state = VaultHabitTracker.onSentencesAnalyzed(state, at(103), 5)
        assertEquals(2, state.streak)
        assertEquals(0, state.freezes)
        state = VaultHabitTracker.onSentencesAnalyzed(state, at(107), 5)
        assertEquals(1, state.streak)
    }

    @Test
    fun zeroSentencesIsNotActivity() {
        val state = VaultHabitTracker.onSentencesAnalyzed(
            VaultHabitState(), at(100), 0
        )
        assertEquals(0, state.streak)
    }

    @Test
    fun backwardsClockIsIgnored() {
        val state = VaultHabitTracker.onSentencesAnalyzed(
            VaultHabitState(), at(100), 5
        )
        val earlier = VaultHabitTracker.onSentencesAnalyzed(state, at(90), 5)
        assertEquals(state, earlier)
    }

    @Test
    fun rolloverMovesTodayToYesterdayOnlyForAdjacentDays() {
        val state = VaultHabitTracker.onSentencesAnalyzed(
            VaultHabitState(), at(100), 12
        )
        val rolled = VaultHabitTracker.rollOver(state, 101)
        assertEquals(12, rolled.yesterdaySentences)
        assertEquals(0, rolled.todaySentences)
        val afterGap = VaultHabitTracker.rollOver(rolled, 105)
        assertEquals(0, afterGap.yesterdaySentences)
    }
}
