/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 YunChan
 */
package org.fcitx.fcitx5.android.data.points

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PointLedgerMathTest {
    private val t = 1_777_000_000_000L

    private fun earn(amount: Int) = PointEvent(t, PointEvent.EARN, amount, "theme-point-earn")
    private fun spend(amount: Int) = PointEvent(t, PointEvent.SPEND, amount, "", "theme")

    @Test
    fun balanceSumsEarnAndSpend() {
        val events = listOf(earn(1), earn(1), earn(1), spend(2))
        assertEquals(1, PointLedgerMath.balance(events))
    }

    @Test
    fun balanceNeverGoesNegative() {
        val events = listOf(earn(1), spend(3))
        assertEquals(0, PointLedgerMath.balance(events))
    }

    @Test
    fun emptyLedgerHasZeroBalance() {
        assertEquals(0, PointLedgerMath.balance(emptyList()))
    }

    @Test
    fun unknownEventTypeIsIgnored() {
        val events = listOf(PointEvent(t, "REFUND", 5, "x"))
        assertEquals(0, PointLedgerMath.balance(events))
    }

    @Test
    fun premiumPriceNeedsFiftyPoints() {
        val events = List(PointPricing.PREMIUM_THEME_PRICE) { earn(1) }
        assertEquals(
            PointPricing.PREMIUM_THEME_PRICE,
            PointLedgerMath.balance(events)
        )
        assertFalse(events.size < PointPricing.PREMIUM_THEME_PRICE)
        assertTrue(
            PointPricing.NORMAL_THEME_PRICE <
                PointPricing.PREMIUM_THEME_PRICE
        )
    }
}
