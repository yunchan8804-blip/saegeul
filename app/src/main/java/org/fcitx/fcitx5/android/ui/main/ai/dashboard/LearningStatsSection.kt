/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 YunChan
 */
package org.fcitx.fcitx5.android.ui.main.ai.dashboard

import android.app.Activity
import android.text.format.DateUtils
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.input.ai.persona.PersonaRegistry
import org.fcitx.fcitx5.android.ui.main.ai.DashboardSnapshot
import org.fcitx.fcitx5.android.ui.main.ai.TypingDnaChartView
import kotlin.math.roundToInt

/** Developer cards with the learned-data counts: summary tiles, n-gram progress, chart, and categories. */
internal class LearningStatsSection(private val activity: Activity) {
    private val sentences: TextView = activity.findViewById(R.id.tv_dash_sentences)
    private val bigrams: TextView = activity.findViewById(R.id.tv_dash_bigrams)
    private val endings: TextView = activity.findViewById(R.id.tv_dash_endings)
    private val phrases: TextView = activity.findViewById(R.id.tv_dash_phrases)
    private val ngramStats: TextView = activity.findViewById(R.id.tv_dash_ngram_stats)
    private val ragStats: TextView = activity.findViewById(R.id.tv_dash_rag_stats)
    private val lastLearned: TextView = activity.findViewById(R.id.tv_dash_last_learned)
    private val chart: TypingDnaChartView = activity.findViewById(R.id.chart_view)
    private val categoryDistribution: LinearLayout = activity.findViewById(R.id.category_distribution_container)

    fun render(snapshot: DashboardSnapshot, animate: Boolean) {
        val stats = snapshot.typingStats
        sentences.text = "${stats.totalSentences}"
        bigrams.text = "${stats.bigramsCount}"
        endings.text = "${stats.endingsCount}"
        phrases.text = "${stats.phrasesCount}"

        chart.setStats(stats, animate = animate)

        ngramStats.text = activity.getString(
            R.string.typing_dna_ngram_stats_line,
            snapshot.ngramUnigrams,
            snapshot.ngramBigrams,
            snapshot.pendingSentences
        )
        ragStats.text = activity.getString(
            R.string.personal_sentence_vault_stats_line,
            snapshot.vaultSentences
        )

        if (snapshot.ngramLastLearnedMs != 0L) {
            lastLearned.visibility = View.VISIBLE
            lastLearned.text = activity.getString(
                R.string.typing_dna_last_learned,
                DateUtils.getRelativeTimeSpanString(
                    snapshot.ngramLastLearnedMs,
                    System.currentTimeMillis(),
                    DateUtils.MINUTE_IN_MILLIS
                )
            )
        } else {
            lastLearned.visibility = View.GONE
        }
    }

    /** Renders one row per [PersonaRegistry.all] entry: share of analyzed sentences and pending count. */
    fun renderCategoryDistribution(
        categoryCounts: Map<String, Int>,
        categoryPending: Map<String, Int>,
        pendingThreshold: Int
    ) {
        categoryDistribution.removeAllViews()
        val total = categoryCounts.values.sum()
        PersonaRegistry.all.forEach { persona ->
            val count = categoryCounts[persona.id] ?: 0
            val pending = categoryPending[persona.id] ?: 0
            val row = activity.layoutInflater.inflate(
                R.layout.view_dashboard_category_row, categoryDistribution, false
            )
            row.findViewById<TextView>(R.id.tv_category_row_label).text = activity.getString(persona.labelRes)
            val barContainer = row.findViewById<View>(R.id.container_category_row_bar)
            val fillView = row.findViewById<View>(R.id.view_category_row_fill)
            val valueText = row.findViewById<TextView>(R.id.tv_category_row_value)
            val emptyText = row.findViewById<TextView>(R.id.tv_category_row_empty)
            if (count <= 0 && pending <= 0) {
                barContainer.visibility = View.GONE
                valueText.visibility = View.GONE
                emptyText.visibility = View.VISIBLE
            } else {
                barContainer.visibility = View.VISIBLE
                valueText.visibility = View.VISIBLE
                emptyText.visibility = View.GONE
                val ratio = if (total > 0) count.toFloat() / total else 0f
                val params = fillView.layoutParams as LinearLayout.LayoutParams
                params.weight = ratio.coerceIn(0f, 1f)
                fillView.layoutParams = params
                val percent = (ratio * 100).roundToInt()
                valueText.text = if (pending > 0) {
                    activity.getString(R.string.vault_category_row_value_with_pending, percent, pending, pendingThreshold)
                } else {
                    "$percent%"
                }
            }
            categoryDistribution.addView(row)
        }
    }
}
