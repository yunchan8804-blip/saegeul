/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 YunChan
 */
package org.fcitx.fcitx5.android.input.ai

import kotlin.math.pow

/**
 * Recency decay shared by the personal learning stores: a count last seen [halfLifeMs] ago
 * weighs half as much as one seen now. Each store keeps its own half-life.
 */
object HalfLifeDecay {

    /** Returns 2^(-elapsed / [halfLifeMs]); a [lastSeenMs] in the future counts as no elapsed time. */
    fun factor(lastSeenMs: Long, nowMs: Long, halfLifeMs: Long): Double =
        2.0.pow(-(nowMs - lastSeenMs).coerceAtLeast(0L).toDouble() / halfLifeMs)
}
