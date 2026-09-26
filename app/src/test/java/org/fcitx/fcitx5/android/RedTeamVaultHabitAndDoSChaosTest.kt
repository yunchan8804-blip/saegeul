/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 YunChan
 */
package org.fcitx.fcitx5.android

import org.fcitx.fcitx5.android.input.ai.VaultHabitState
import org.fcitx.fcitx5.android.input.ai.VaultHabitTracker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Red Team Adversarial Unit Tests: VaultHabitTracker DoS Resistance & Integer Overflow Protection.
 *
 * Verifies that:
 * 1. Massive time skips (e.g. 3 billion days) do not cause 32-bit integer overflow in missedDays
 *    calculation (which could turn negative and cheat streaks) or hang the thread.
 * 2. 10 million day skip does not execute multi-million iteration repeat loop causing ANR.
 * 3. Daily sentence count addition saturates at Int.MAX_VALUE instead of wrapping to negative.
 * 4. Backwards time travel does not rewind todayDayIndex or wipe existing sentences in rollOver.
 * 5. Zero or negative analyzed sentences strictly return unchanged original state.
 */
class RedTeamVaultHabitAndDoSChaosTest {

    private companion object {
        const val DAY_MS = 86_400_000L
    }

    /**
     * Test 1: Massive time skip does not hang in repeat loop or overflow missedDays to negative.
     *
     * Attack Vector: Skipping 3,000,000,000 days causes `(day - lastActiveDayIndex - 1).toInt()`
     * to overflow 32-bit signed Int to negative (-1,294,967,296). If coerced to 0 or evaluated
     * as negative, freezes are never decremented and streak is improperly preserved/incremented.
     * The operation must complete in <= 10ms with streak reset to 1 and freezes exhausted to 0.
     */
    @Test
    fun `massive time skip does not hang in repeat loop or overflow missedDays to negative`() {
        val state = VaultHabitState(
            streak = 50,
            freezes = 2,
            lastActiveDayIndex = 10L,
            todayDayIndex = 10L,
            todaySentences = 5
        )
        val day = 10L + 3_000_000_000L
        val now = day * DAY_MS

        val startNano = System.nanoTime()
        val result = VaultHabitTracker.onSentencesAnalyzed(state, now, 1)
        val elapsedMs = (System.nanoTime() - startNano) / 1_000_000

        assertTrue(
            "Massive time skip must complete within 10ms without looping/hanging, took ${elapsedMs}ms",
            elapsedMs <= 10L
        )
        assertEquals("Streak must reset to 1 after 3B days skip", 1, result.streak)
        assertEquals("Freezes must be exhausted to 0 after 3B days skip", 0, result.freezes)
        assertEquals(day, result.lastActiveDayIndex)
    }

    /**
     * Test 2: Ten million day skip completes in sub-millisecond without ANR.
     *
     * Attack Vector: Skipping 10,000,000 days could cause `repeat(10_000_000)` CPU spin loop
     * blocking the main/input thread and causing an ANR.
     * The operation must complete within 5ms.
     */
    @Test
    fun `ten million day skip completes in sub-millisecond without ANR`() {
        val state = VaultHabitState(
            streak = 10,
            freezes = 2,
            lastActiveDayIndex = 100L
        )
        val day = 100L + 10_000_000L
        val now = day * DAY_MS

        val startNano = System.nanoTime()
        val result = VaultHabitTracker.onSentencesAnalyzed(state, now, 1)
        val elapsedMs = (System.nanoTime() - startNano) / 1_000_000

        assertTrue(
            "Ten million day skip must complete within 5ms without ANR, took ${elapsedMs}ms",
            elapsedMs <= 5L
        )
        assertEquals("Streak must reset to 1 after 10M days skip", 1, result.streak)
        assertEquals("Freezes must be exhausted to 0 after 10M days skip", 0, result.freezes)
        assertEquals(day, result.lastActiveDayIndex)
    }

    /**
     * Test 3: Sentence count addition saturates at Int MAX_VALUE without negative overflow.
     *
     * Attack Vector: Adding large sentence counts (e.g. 2,000,000,000 + 200,000,000) causes 32-bit
     * integer overflow, turning today's sentences negative.
     * Must safely saturate at Int.MAX_VALUE.
     */
    @Test
    fun `sentence count addition saturates at Int MAX_VALUE without negative overflow`() {
        val state = VaultHabitState(
            todayDayIndex = 50L,
            lastActiveDayIndex = 50L,
            todaySentences = 2_000_000_000
        )
        val now = 50L * DAY_MS + 1_000L
        val result = VaultHabitTracker.onSentencesAnalyzed(state, now, 200_000_000)

        assertEquals(
            "Sentence count addition must saturate at Int.MAX_VALUE without negative overflow",
            Int.MAX_VALUE,
            result.todaySentences
        )
    }

    /**
     * Test 4: Time travel backwards in rollOver never rewinds todayDayIndex or wipes today sentences.
     *
     * Attack Vector: Backward device clock change to day 90L when current state is at day 100L
     * causes rollOver to reset todayDayIndex to 90L and clear todaySentences to 0.
     * rollOver must enforce monotonicity: preserve todayDayIndex at 100L and todaySentences at 50.
     */
    @Test
    fun `time travel backwards in rollOver never rewinds todayDayIndex or wipes today sentences`() {
        val state = VaultHabitState(
            todayDayIndex = 100L,
            todaySentences = 50
        )
        val rolled = VaultHabitTracker.rollOver(state, 90L)

        assertEquals("todayDayIndex must not rewind to past day", 100L, rolled.todayDayIndex)
        assertEquals("todaySentences must be preserved and not wiped to 0", 50, rolled.todaySentences)
    }

    /**
     * Test 5: Zero or negative analyzed sentences strictly returns unchanged state.
     *
     * Invariant: Passing analyzed <= 0 must immediately return the exact identical state instance.
     */
    @Test
    fun `zero or negative analyzed sentences strictly returns unchanged state`() {
        val state = VaultHabitState(
            streak = 5,
            freezes = 1,
            lastActiveDayIndex = 20L,
            todayDayIndex = 20L,
            todaySentences = 10,
            yesterdaySentences = 8
        )
        val now = 20L * DAY_MS + 3_600_000L

        val resultZero = VaultHabitTracker.onSentencesAnalyzed(state, now, 0)
        assertSame("Zero analyzed sentences must return unchanged identical state", state, resultZero)

        val resultNegative = VaultHabitTracker.onSentencesAnalyzed(state, now, -50)
        assertSame("Negative analyzed sentences must return unchanged identical state", state, resultNegative)
    }
}
