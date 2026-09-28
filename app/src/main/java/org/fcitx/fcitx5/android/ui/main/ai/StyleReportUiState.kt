/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 YunChan
 */
package org.fcitx.fcitx5.android.ui.main.ai

import org.fcitx.fcitx5.android.input.ai.TypingDnaStats
import org.fcitx.fcitx5.android.input.ai.metrics.PredictionMetricsStore
import org.fcitx.fcitx5.android.input.ai.persona.PersonaRegistry
import kotlin.math.roundToInt

/**
 * Pure, Context-free mapping of vault data into the "내 말투 리포트" screen's content. Kept free of
 * Android types (like [LearningStatusUiState]) so it stays plain-JVM-testable; the activity only
 * resolves string resources and feeds [stats] into the two reused [TypingDnaChartView] sections
 * (accumulation bars, tone balance) it already knows how to draw.
 */
data class StyleReportUiState(
    /** True when there is nothing learned yet; the activity shows a single empty-state message instead of any card. */
    val isEmpty: Boolean,
    /** Non-null exactly when [isEmpty] is false; fed directly into the accumulation/tone-balance chart sections. */
    val stats: TypingDnaStats?,
    /** Real (not weighted) share of recorded sentences per app category, highest first; only categories with at least one sentence. */
    val categoryRows: List<CategoryRow>,
    val recentTotalSentences: Int,
    val recentDailyMaximum: Int,
    /** "A → B" chips, highest weight first, capped at [MAX_CHIPS]. */
    val topTransitionChips: List<String>,
    /** Habitual sentence endings, most common first, capped at [MAX_CHIPS]. */
    val topEndingChips: List<String>
) {
    data class CategoryRow(
        val personaId: String,
        val labelRes: Int,
        val percent: Int
    )

    companion object {
        private const val MAX_CHIPS = 6

        /**
         * [vaultCategoryCounts] must be the actual per-sentence tally
         * ([org.fcitx.fcitx5.android.input.ai.rag.PersonalSentenceVault.categoryCounts]), not
         * [TypingDnaStats.categoryCounts] (a weighted count derived from compiled persona profiles) -
         * using the latter here would misreport which app the user actually types in most.
         */
        fun from(
            stats: TypingDnaStats,
            vaultCategoryCounts: Map<String, Int>,
            metrics: PredictionMetricsStore.Summary
        ): StyleReportUiState {
            if (stats.totalSentences <= 0) {
                return StyleReportUiState(
                    isEmpty = true,
                    stats = null,
                    categoryRows = emptyList(),
                    recentTotalSentences = 0,
                    recentDailyMaximum = 0,
                    topTransitionChips = emptyList(),
                    topEndingChips = emptyList()
                )
            }

            val totalCategorized = vaultCategoryCounts.values.sum()
            val categoryRows = PersonaRegistry.all
                .mapNotNull { persona ->
                    val count = vaultCategoryCounts[persona.id] ?: 0
                    if (count <= 0) return@mapNotNull null
                    val percent = if (totalCategorized > 0) (count * 100f / totalCategorized).roundToInt() else 0
                    CategoryRow(personaId = persona.id, labelRes = persona.labelRes, percent = percent)
                }
                .sortedByDescending { it.percent }

            return StyleReportUiState(
                isEmpty = false,
                stats = stats,
                categoryRows = categoryRows,
                recentTotalSentences = metrics.recent.sumOf { it.learnedSentences },
                recentDailyMaximum = metrics.recent.maxOfOrNull { it.learnedSentences } ?: 0,
                topTransitionChips = stats.topBigrams.take(MAX_CHIPS).map { "${it.prev} → ${it.next}" },
                topEndingChips = stats.topEndings.take(MAX_CHIPS)
            )
        }
    }
}
