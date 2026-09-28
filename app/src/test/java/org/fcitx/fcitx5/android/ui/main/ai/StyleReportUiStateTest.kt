/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 YunChan
 */
package org.fcitx.fcitx5.android.ui.main.ai

import org.fcitx.fcitx5.android.input.ai.TopBigramStat
import org.fcitx.fcitx5.android.input.ai.TypingDnaStats
import org.fcitx.fcitx5.android.input.ai.metrics.PredictionMetricsStore
import org.fcitx.fcitx5.android.input.ai.persona.PersonaRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Unit tests for [StyleReportUiState], the "내 말투 리포트" screen's pure state model. */
class StyleReportUiStateTest {

    private fun stats(
        totalSentences: Int = 10,
        topBigrams: List<TopBigramStat> = emptyList(),
        topEndings: List<String> = emptyList()
    ) = TypingDnaStats(
        level = 1,
        levelTitle = "",
        levelProgressPercent = 0,
        nextLevelTargetSentences = 0,
        totalSentences = totalSentences,
        bigramsCount = 0,
        endingsCount = 0,
        phrasesCount = 0,
        topBigrams = topBigrams,
        topEndings = topEndings,
        honorificRatio = 0f,
        informalRatio = 0f,
        messengerSentencesRatio = 0f,
        workSentencesRatio = 0f,
        generalSentencesRatio = 0f,
        lastUpdatedTimestamp = 0L
    )

    private fun metrics(recent: List<PredictionMetricsStore.DayStat> = emptyList()) = PredictionMetricsStore.Summary(
        totalShown = 0,
        totalAccepted = 0,
        acceptRate = 0f,
        personalShare = 0f,
        keystrokesSaved = 0,
        typoCorrected = 0,
        learnedSentences = 0,
        learnedWords = 0,
        activeDays = 0,
        firstDay = null,
        lastDay = null,
        recent = recent
    )

    private fun dayStat(day: String, learnedSentences: Int) = PredictionMetricsStore.DayStat(
        day = day,
        shown = 0,
        accepted = 0,
        acceptedPersonal = 0,
        keystrokesSaved = 0,
        typoCorrected = 0,
        learnedSentences = learnedSentences,
        learnedWords = 0
    )

    @Test
    fun noSentencesLearnedYetIsAFullEmptyState() {
        val state = StyleReportUiState.from(
            stats = stats(totalSentences = 0),
            vaultCategoryCounts = mapOf("messenger" to 5),
            metrics = metrics(recent = listOf(dayStat("2026-09-20", 3))),
        )

        assertTrue(state.isEmpty)
        assertEquals(null, state.stats)
        assertTrue(state.categoryRows.isEmpty())
        assertEquals(0, state.recentTotalSentences)
        assertEquals(0, state.recentDailyMaximum)
        assertTrue(state.topTransitionChips.isEmpty())
        assertTrue(state.topEndingChips.isEmpty())
    }

    @Test
    fun categoryRowsUseTheRealVaultTallyNotAWeightedCount() {
        // Deliberately unbalanced so a weighted stat-derived count (e.g. bigram-based) would rank
        // differently from the actual sentence tally used here.
        val counts = mapOf("messenger" to 1, "work" to 6, "general" to 3)

        val state = StyleReportUiState.from(
            stats = stats(totalSentences = 10),
            vaultCategoryCounts = counts,
            metrics = metrics(),
        )

        assertEquals(3, state.categoryRows.size)
        assertEquals("work", state.categoryRows[0].personaId)
        assertEquals(60, state.categoryRows[0].percent)
        assertEquals(PersonaRegistry.byId("work")!!.labelRes, state.categoryRows[0].labelRes)
        assertEquals("general", state.categoryRows[1].personaId)
        assertEquals(30, state.categoryRows[1].percent)
        assertEquals("messenger", state.categoryRows[2].personaId)
        assertEquals(10, state.categoryRows[2].percent)
    }

    @Test
    fun categoryRowsOmitCategoriesWithNoRecordedSentences() {
        val state = StyleReportUiState.from(
            stats = stats(totalSentences = 5),
            vaultCategoryCounts = mapOf("messenger" to 4, "work" to 0, "email" to 0),
            metrics = metrics(),
        )

        assertEquals(listOf("messenger"), state.categoryRows.map { it.personaId })
    }

    @Test
    fun categoryRowsAreEmptyWhenNothingHasBeenCategorizedYet() {
        val state = StyleReportUiState.from(
            stats = stats(totalSentences = 5),
            vaultCategoryCounts = emptyMap(),
            metrics = metrics(),
        )

        assertTrue(state.categoryRows.isEmpty())
    }

    @Test
    fun topTransitionChipsAreFormattedAndCappedAtSix() {
        val bigrams = (1..8).map { TopBigramStat(prev = "앞$it", next = "뒤$it", weight = (10 - it).toFloat()) }

        val state = StyleReportUiState.from(
            stats = stats(totalSentences = 5, topBigrams = bigrams),
            vaultCategoryCounts = emptyMap(),
            metrics = metrics(),
        )

        assertEquals(6, state.topTransitionChips.size)
        assertEquals("앞1 → 뒤1", state.topTransitionChips.first())
    }

    @Test
    fun topEndingChipsAreCappedAtSix() {
        val endings = (1..8).map { "말끝$it" }

        val state = StyleReportUiState.from(
            stats = stats(totalSentences = 5, topEndings = endings),
            vaultCategoryCounts = emptyMap(),
            metrics = metrics(),
        )

        assertEquals(endings.take(6), state.topEndingChips)
    }

    @Test
    fun recentTotalsSumLearnedSentencesAndFindTheDailyMaximum() {
        val state = StyleReportUiState.from(
            stats = stats(totalSentences = 5),
            vaultCategoryCounts = emptyMap(),
            metrics = metrics(
                recent = listOf(dayStat("2026-09-19", 2), dayStat("2026-09-20", 5), dayStat("2026-09-21", 1))
            ),
        )

        assertEquals(8, state.recentTotalSentences)
        assertEquals(5, state.recentDailyMaximum)
    }
}
