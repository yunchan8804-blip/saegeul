/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 YunChan
 */
package org.fcitx.fcitx5.android.ui.main.ai

import org.fcitx.fcitx5.android.input.ai.metrics.PredictionMetricsStore
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Unit tests for [VaultTimelineData], the pure mapping from [PredictionMetricsStore.Summary]
 * to the "내 언어 금고" growth timeline bars.
 */
class VaultTimelineDataTest {

    private fun dayStat(
        day: String,
        learnedWords: Int = 0,
        learnedSentences: Int = 0,
        accepted: Int = 0
    ) = PredictionMetricsStore.DayStat(
        day = day,
        shown = 0,
        accepted = accepted,
        acceptedPersonal = 0,
        keystrokesSaved = 0,
        typoCorrected = 0,
        learnedSentences = learnedSentences,
        learnedWords = learnedWords
    )

    private fun summaryWith(days: List<PredictionMetricsStore.DayStat>) = PredictionMetricsStore.Summary(
        totalShown = 0,
        totalAccepted = 0,
        acceptRate = 0f,
        personalShare = 0f,
        keystrokesSaved = 0,
        typoCorrected = 0,
        learnedSentences = 0,
        learnedWords = 0,
        activeDays = 0,
        firstDay = days.firstOrNull()?.day,
        lastDay = days.lastOrNull()?.day,
        recent = days
    )

    @Test
    fun fromSummaryReturnsOneBarPerRecentDay() {
        val days = (1..30).map { i -> dayStat(day = "2026-08-%02d".format(i), learnedWords = i) }

        val bars = VaultTimelineData.fromSummary(summaryWith(days))

        assertEquals(30, bars.size)
    }

    @Test
    fun fromSummaryNormalizesHeightRatioToMaxOne() {
        val days = listOf(
            dayStat("2026-08-01", learnedWords = 2),
            dayStat("2026-08-02", learnedWords = 10),
            dayStat("2026-08-03", learnedSentences = 5)
        )

        val bars = VaultTimelineData.fromSummary(summaryWith(days))

        assertEquals(0.2f, bars[0].heightRatio, 0.0001f)
        assertEquals(1.0f, bars[1].heightRatio, 0.0001f)
        assertEquals(0.5f, bars[2].heightRatio, 0.0001f)
    }

    @Test
    fun fromSummaryEmptyDayHasZeroHeightRatio() {
        val days = listOf(
            dayStat("2026-08-01"),
            dayStat("2026-08-02", learnedWords = 4)
        )

        val bars = VaultTimelineData.fromSummary(summaryWith(days))

        assertEquals(0f, bars[0].heightRatio, 0.0001f)
        assertEquals(0, bars[0].value)
    }

    @Test
    fun formatLabelRendersMonthSlashDay() {
        assertEquals("8/1", VaultTimelineData.formatLabel("2026-08-01"))
        assertEquals("9/6", VaultTimelineData.formatLabel("2026-09-06"))
        assertEquals("12/25", VaultTimelineData.formatLabel("2026-12-25"))
    }
}
