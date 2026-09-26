/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 YunChan
 */
package org.fcitx.fcitx5.android.input.ai.rag

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PersonalGraphEnrichmentCycleTest {

    @Test
    fun firstEverCycleOnlyNeedsThirtySentences() {
        assertFalse(
            PersonalGraphEnrichmentCycle.shouldStart(
                hasExistingGraph = false, newSentenceCount = 9, msSinceLastBuild = 0L, manual = false
            )
        )
        assertTrue(
            PersonalGraphEnrichmentCycle.shouldStart(
                hasExistingGraph = false, newSentenceCount = 10, msSinceLastBuild = 0L, manual = false
            )
        )
    }

    @Test
    fun subsequentCycleNeedsBothTenSentencesAndOneHour() {
        val justUnderInterval = PersonalGraphEnrichmentCycle.MIN_INTERVAL_MS - 1
        val exactlyInterval = PersonalGraphEnrichmentCycle.MIN_INTERVAL_MS

        assertFalse(
            PersonalGraphEnrichmentCycle.shouldStart(
                hasExistingGraph = true, newSentenceCount = 10, msSinceLastBuild = justUnderInterval, manual = false
            )
        )
        assertFalse(
            PersonalGraphEnrichmentCycle.shouldStart(
                hasExistingGraph = true, newSentenceCount = 9, msSinceLastBuild = exactlyInterval, manual = false
            )
        )
        assertTrue(
            PersonalGraphEnrichmentCycle.shouldStart(
                hasExistingGraph = true, newSentenceCount = 10, msSinceLastBuild = exactlyInterval, manual = false
            )
        )
    }

    @Test
    fun manualBypassesBothThresholdsRegardlessOfGraphHistory() {
        assertTrue(
            PersonalGraphEnrichmentCycle.shouldStart(
                hasExistingGraph = false, newSentenceCount = 0, msSinceLastBuild = 0L, manual = true
            )
        )
        assertTrue(
            PersonalGraphEnrichmentCycle.shouldStart(
                hasExistingGraph = true, newSentenceCount = 0, msSinceLastBuild = 0L, manual = true
            )
        )
        assertTrue(
            PersonalGraphEnrichmentCycle.shouldStart(
                hasExistingGraph = true, newSentenceCount = 9, msSinceLastBuild = 1L, manual = true
            )
        )
    }
}
