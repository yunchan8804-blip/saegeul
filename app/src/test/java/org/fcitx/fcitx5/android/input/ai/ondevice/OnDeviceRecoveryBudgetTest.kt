/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 YunChan
 */
package org.fcitx.fcitx5.android.input.ai.ondevice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OnDeviceRecoveryBudgetTest {

    @Test
    fun `up to three recoveries are granted within the window`() {
        val budget = OnDeviceRecoveryBudget(windowMs = 600_000L, maxRecoveries = 3)

        assertTrue(budget.tryConsume(0L))
        assertTrue(budget.tryConsume(1_000L))
        assertTrue(budget.tryConsume(2_000L))
        assertEquals(3, budget.consumedInWindow(2_000L))
    }

    @Test
    fun `a fourth recovery within the window is refused`() {
        val budget = OnDeviceRecoveryBudget(windowMs = 600_000L, maxRecoveries = 3)
        budget.tryConsume(0L)
        budget.tryConsume(1_000L)
        budget.tryConsume(2_000L)

        assertFalse(budget.tryConsume(3_000L))
        assertEquals(3, budget.consumedInWindow(3_000L))
    }

    @Test
    fun `a recovery outside the window is granted again as old ones expire`() {
        val budget = OnDeviceRecoveryBudget(windowMs = 600_000L, maxRecoveries = 3)
        budget.tryConsume(0L)
        budget.tryConsume(1_000L)
        budget.tryConsume(2_000L)
        assertFalse(budget.tryConsume(3_000L))

        assertTrue(budget.tryConsume(602_001L))
        assertEquals(1, budget.consumedInWindow(602_001L))
    }

    @Test
    fun `reset clears all recorded consumptions`() {
        val budget = OnDeviceRecoveryBudget(windowMs = 600_000L, maxRecoveries = 3)
        budget.tryConsume(0L)
        budget.tryConsume(1_000L)
        budget.tryConsume(2_000L)
        assertFalse(budget.tryConsume(3_000L))

        budget.reset()

        assertEquals(0, budget.consumedInWindow(3_000L))
        assertTrue(budget.tryConsume(3_000L))
    }
}
