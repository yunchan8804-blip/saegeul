/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 YunChan
 */
package org.fcitx.fcitx5.android.input.ai

import org.junit.Assert.assertEquals
import org.junit.Test

class HalfLifeDecayTest {

    private val halfLifeMs = 30L * 24 * 60 * 60 * 1000

    @Test
    fun halvesOncePerHalfLife() {
        assertEquals(1.0, HalfLifeDecay.factor(lastSeenMs = 0L, nowMs = 0L, halfLifeMs = halfLifeMs), 0.0)
        assertEquals(0.5, HalfLifeDecay.factor(lastSeenMs = 0L, nowMs = halfLifeMs, halfLifeMs = halfLifeMs), 0.0)
        assertEquals(0.25, HalfLifeDecay.factor(lastSeenMs = 0L, nowMs = 2 * halfLifeMs, halfLifeMs = halfLifeMs), 0.0)
    }

    @Test
    fun lastSeenInTheFutureDoesNotBoost() {
        assertEquals(1.0, HalfLifeDecay.factor(lastSeenMs = 5_000L, nowMs = 1_000L, halfLifeMs = halfLifeMs), 0.0)
    }
}
