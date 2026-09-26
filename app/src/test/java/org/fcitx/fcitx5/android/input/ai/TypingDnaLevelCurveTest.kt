/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 YunChan
 */
package org.fcitx.fcitx5.android.input.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TypingDnaLevelCurveTest {

    @Test
    fun historicalThresholdsArePreserved() {
        assertEquals(0, TypingDnaLevelCurve.thresholdFor(1))
        assertEquals(15, TypingDnaLevelCurve.thresholdFor(2))
        assertEquals(45, TypingDnaLevelCurve.thresholdFor(3))
        assertEquals(100, TypingDnaLevelCurve.thresholdFor(4))
        assertEquals(200, TypingDnaLevelCurve.thresholdFor(5))
    }

    @Test
    fun legacyLevelsKeepTitles() {
        assertEquals("새싹 학습자", TypingDnaLevelCurve.titleFor(1))
        assertEquals("언어 지문 마스터", TypingDnaLevelCurve.titleFor(5))
    }

    @Test
    fun thresholdsAreStrictlyMonotonicUpToMaxLevel() {
        var previous = TypingDnaLevelCurve.thresholdFor(1)
        for (level in 2..TypingDnaLevelCurve.MAX_LEVEL) {
            val threshold = TypingDnaLevelCurve.thresholdFor(level)
            assertTrue("level $level not monotonic", threshold > previous)
            previous = threshold
        }
    }

    @Test
    fun level6SitsJustAboveLegacyCap() {
        val threshold = TypingDnaLevelCurve.thresholdFor(6)
        assertTrue(threshold > 200)
        assertTrue(threshold < 300)
    }

    @Test
    fun maxLevelNeedsAMillionSentences() {
        val threshold = TypingDnaLevelCurve.thresholdFor(TypingDnaLevelCurve.MAX_LEVEL)
        assertTrue("max too cheap: $threshold", threshold in 500_000..1_500_000)
    }

    @Test
    fun levelForMatchesThresholds() {
        assertEquals(1, TypingDnaLevelCurve.levelFor(0))
        assertEquals(1, TypingDnaLevelCurve.levelFor(14))
        assertEquals(2, TypingDnaLevelCurve.levelFor(15))
        assertEquals(5, TypingDnaLevelCurve.levelFor(200))
        assertEquals(
            TypingDnaLevelCurve.MAX_LEVEL,
            TypingDnaLevelCurve.levelFor(
                TypingDnaLevelCurve.thresholdFor(TypingDnaLevelCurve.MAX_LEVEL)
            )
        )
        assertEquals(
            TypingDnaLevelCurve.MAX_LEVEL,
            TypingDnaLevelCurve.levelFor(Int.MAX_VALUE)
        )
    }

    @Test
    fun describeProgressStaysWithinBounds() {
        val progress = TypingDnaLevelCurve.describe(120)
        assertEquals(4, progress.level)
        assertEquals("정밀 문체 동기화", progress.title)
        assertEquals(200, progress.nextTargetSentences)
        assertTrue(progress.progressPercent in 0..99)
        val maxed = TypingDnaLevelCurve.describe(Int.MAX_VALUE)
        assertEquals(TypingDnaLevelCurve.MAX_LEVEL, maxed.level)
        assertEquals(100, maxed.progressPercent)
    }

    @Test
    fun rewardBonusPointsGrowWithLevel() {
        assertEquals(0, TypingDnaLevelCurve.rewardBonusPoints(1))
        assertEquals(1, TypingDnaLevelCurve.rewardBonusPoints(25))
        assertEquals(2, TypingDnaLevelCurve.rewardBonusPoints(100))
        assertEquals(3, TypingDnaLevelCurve.rewardBonusPoints(1000))
    }
}
